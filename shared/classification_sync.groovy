// classification_sync.groovy — mirroring the Data X-Ray classification
// catalogue into the Collibra classifications domain.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// The transport that fetches the catalogue is the caller's concern; this file
// takes the parsed `data` list and does everything Collibra-side.
//
// Sync semantics:
//   – Upsert by Data X-Ray ID. Each Collibra asset carries a "Data X-Ray ID"
//     attribute that stores the remote id; we match on that, so a Data X-Ray-side
//     rename updates the existing asset rather than spawning a duplicate.
//   – Name fallback: if no ID match is found but an asset with the same name
//     already exists in the domain (e.g. created by an older name-based sync,
//     so it has no Data X-Ray ID attribute), we adopt that asset and stamp the
//     ID onto it instead of attempting a create Collibra rejects with
//     termAlreadyExists. The next run then matches it by ID. Each existing
//     asset is adopted at most once, so two remote items sharing a name surface
//     the second as a normal per-item failure rather than colliding silently.
//   – Name disambiguation: Collibra enforces full-name uniqueness per domain.
//     When one name is used by more than one Data X-Ray resource (e.g. two
//     annotators both named "VAT Number", one Named Entity and one Regular
//     Expression), each such asset's Collibra name is suffixed to keep them
//     distinct — "VAT Number (Named Entity)", "VAT Number (Regular Expression)".
//     The suffix is the subtype (the discriminator shown in the Data X-Ray UI),
//     falling back to type, then to a short id fragment if those still tie.
//     Names used by a single resource are left untouched.
//   – When matched by ID, if the Data X-Ray name has diverged from the Collibra
//     asset name we rename the Collibra asset to match.
//   – Every synced asset is tagged with 'dataxray-classification-sync'. Assets
//     in the domain that carry this tag but are no longer in the current Data
//     X-Ray response are RETIRED (status Obsolete) — never deleted. Saved
//     search queries and imported file assets hold relations to these
//     classifications, and a rerun must be able to say "this criterion was
//     deleted in Data X-Ray" rather than find a dangling reference. If the
//     classification reappears in Data X-Ray, the asset is reactivated
//     (status Candidate). Untagged assets in the domain are never touched,
//     so anything added by hand is safe.
//   – If Data X-Ray returns zero classifications, the retirement step is
//     skipped to avoid a transient/broken response retiring all assets.

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}

// Sync the catalogue. `dataxrayUrl` (no trailing slash) prefixes the relative
// link/searchLink paths Data X-Ray returns. Returns
// [created:, updated:, retired:, skipped:, failed:, failures: [String]].
// opts (optional):
//   indexIdAttrTypeId — when set, an item's `indexId` (Data X-Ray's numeric
//                       search-index id) is stamped on the asset (Edge edition).
//   noRetireTypeIds   — asset type ids that must NOT be retired this run (the
//                       caller saw an incomplete list for them; Edge fallback).
//   Items may carry `partial: true`: the item is known to exist in Data X-Ray
//   (presence, name and ids are authoritative) but its description/link/subtype
//   were NOT fetched this run — those attributes are left untouched.
def syncClassificationCatalog(List classifications, String dataxrayUrl, Map opts = [:]) {
    def ids = dxrModelIds()
    final String SYNC_TAG = dxrClassificationSyncTag()

    // Map remote type → Collibra asset type UUID.
    def assetTypeIdByType = [
        CLASSIFICATION: ids.classificationTypeId,
        ANNOTATOR     : ids.annotatorTypeId,
        EXTRACTOR     : ids.extractorTypeId,
        LABEL         : ids.labelTypeId,
    ]

    // --- Index existing assets in the target domain ----------------------------

    def existingById = fetchAllAssetsInDomain(ids.classificationsDomainId)
    loggerApi.info("Found ${existingById.size()} existing asset(s) in classifications domain")

    // Name → asset index for the name-based adoption fallback below. Collibra
    // enforces full-name uniqueness per domain (the source of termAlreadyExists),
    // so within this domain getName() is effectively a unique key.
    def existingByName = [:]
    existingById.values().each { a -> existingByName[a.getName()] = a }

    // Map Data X-Ray ID → Collibra asset UUID, restricted to assets in this domain.
    def assetIdByDataxrayId = fetchAssetIdsByDataxrayId(ids.dataxrayIdAttrTypeId, existingById.keySet())
    loggerApi.info("Indexed ${assetIdByDataxrayId.size()} asset(s) with a Data X-Ray ID attribute")

    // --- Disambiguate colliding asset names --------------------------------------

    // Keyed by Data X-Ray id so the loop can look up each item's resolved name.
    def itemsByName = [:]
    classifications.each { c ->
        def nm = c?.name
        def ty = c?.type as String
        if (nm && assetTypeIdByType[ty]) {
            def key = nm.toString()
            def list = itemsByName[key]
            if (list == null) { list = []; itemsByName[key] = list }
            list << c
        }
    }

    def disambiguatedName = [:]
    itemsByName.each { baseName, items ->
        if (items.size() <= 1) return
        def labels = items.collect { humanize(it.subtype as String) ?: typeLabel(it.type as String) }
        def labelCounts = labels.countBy { it }
        items.eachWithIndex { item, i ->
            def label = labels[i]
            if ((labelCounts[label] ?: 0) > 1) {
                // subtype/type still ties (e.g. two same-subtype resources) — append
                // a short, stable id fragment so the names can never collide.
                def idFrag = (item?.id == null ? '' : item.id.toString()).replaceAll('-', '').take(8)
                label = label ? "${label} ${idFrag}" : idFrag
            }
            def did = item?.id == null ? null : item.id.toString().trim()
            if (did) disambiguatedName[did] = "${baseName} (${label})".toString()
        }
    }
    if (!disambiguatedName.isEmpty()) {
        loggerApi.info("Disambiguated ${disambiguatedName.size()} colliding asset name(s): ${disambiguatedName.values().join(', ')}")
    }

    // --- Upsert each classification --------------------------------------------

    int created = 0
    int updated = 0
    int failed  = 0
    int skipped = 0
    def failures = []
    def touchedAssetIds = [] as Set

    classifications.eachWithIndex { classification, idx ->
        def name = classification?.name
        def dataxrayId = classification?.id == null ? null : classification.id.toString().trim()
        try {
            if (!name) {
                skipped++
                loggerApi.warn("Skipping classification at index ${idx}: missing 'name'")
                return
            }
            if (!dataxrayId) {
                skipped++
                loggerApi.warn("Skipping '${name}': missing Data X-Ray 'id'")
                return
            }

            def type = classification.type as String
            def assetTypeId = assetTypeIdByType[type]
            if (!assetTypeId) {
                skipped++
                loggerApi.warn("Skipping '${name}': unknown type '${type}'")
                return
            }

            // The Collibra asset name. Disambiguated (see disambiguatedName) only
            // when this name is shared by more than one Data X-Ray resource, so
            // collisions get distinct names rather than failing a create with
            // termAlreadyExists; otherwise the bare Data X-Ray name is used.
            def assetName = (dataxrayId && disambiguatedName.containsKey(dataxrayId)) ? disambiguatedName[dataxrayId] : name.toString()

            // Resolution: match by Data X-Ray ID, else adopt a same-named asset
            // already in the domain, else create a new asset.
            UUID assetId = null
            boolean isUpdate = false
            String previousName = null
            boolean wasRetired = false

            def idMatch = assetIdByDataxrayId[dataxrayId]
            if (idMatch) {
                assetId = idMatch
                isUpdate = true
                previousName = existingById[idMatch]?.getName()
                wasRetired = existingById[idMatch]?.getStatus()?.getId() == ids.obsoleteStatusId
            }

            // Fallback: a same-named asset already exists in the domain but has no
            // Data X-Ray ID attribute (e.g. created by an older name-based sync).
            // Adopt it rather than attempting a create Collibra would reject with
            // termAlreadyExists; the ID attribute is stamped below, so the next run
            // matches it by ID directly. Guard on touchedAssetIds so each existing
            // asset is adopted at most once.
            if (assetId == null) {
                def nameMatch = existingByName[assetName]
                if (nameMatch && !touchedAssetIds.contains(nameMatch.getId())) {
                    assetId = nameMatch.getId()
                    isUpdate = true
                    previousName = nameMatch.getName()
                    wasRetired = nameMatch.getStatus()?.getId() == ids.obsoleteStatusId
                }
            }

            if (assetId == null) {
                def asset = assetApi.addAsset(com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest.builder()
                    .name(assetName)
                    .displayName(assetName)
                    .domainId(ids.classificationsDomainId)
                    .typeId(assetTypeId)
                    .build())
                assetId = asset.getId()
            }

            // Rename if the desired (possibly type-suffixed) name has diverged from
            // what's in Collibra — covers both Data X-Ray-side renames and an asset
            // newly becoming/ceasing to be a cross-type collision. Reactivate in
            // the same change if a previous sync had retired this classification
            // and it has now reappeared in Data X-Ray.
            def needsRename = isUpdate && previousName != null && previousName != assetName
            if (needsRename || wasRetired) {
                try {
                    def change = com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder().id(assetId)
                    if (needsRename) { change.name(assetName).displayName(assetName) }
                    if (wasRetired)  { change.statusId(ids.candidateStatusId) }
                    assetApi.changeAsset(change.build())
                    if (needsRename) { loggerApi.info("Renamed asset [${assetId}]: '${previousName}' → '${assetName}'") }
                    if (wasRetired)  { loggerApi.info("Reactivated previously retired asset '${assetName}' [${assetId}]") }
                } catch (Exception renameEx) {
                    loggerApi.warn("Failed to rename/reactivate '${previousName}' → '${assetName}' on ${assetId}: ${renameEx.message}")
                }
            }

            // Tag the asset (idempotent — addAssetTags is a no-op for tags already present)
            try {
                assetApi.addAssetTags(com.collibra.dgc.core.api.dto.instance.asset.AddAssetTagsRequest.builder()
                    .assetId(assetId)
                    .tagNames([SYNC_TAG])
                    .build())
            } catch (Exception tagEx) {
                loggerApi.warn("Failed to apply sync tag on ${assetId}: ${tagEx.message}")
            }

            // setAssetAttributes replaces all values of the given type — works for
            // both fresh creates and updates. Stamping the Data X-Ray ID here keys
            // first-time-created assets for future runs.
            def attrErrors = []
            syncAttribute(attrErrors, assetId, ids.dataxrayIdAttrTypeId,  dataxrayId, 'dataxrayId')
            if (opts.indexIdAttrTypeId && classification.indexId != null && !classification.indexId.toString().isEmpty()) {
                syncAttribute(attrErrors, assetId, opts.indexIdAttrTypeId, classification.indexId.toString(), 'indexId')
            }
            if (!classification.partial) {
                syncAttribute(attrErrors, assetId, ids.descriptionAttrTypeId, classification.description, 'description')
                syncAttribute(attrErrors, assetId, ids.linkAttrTypeId,        classification.link       ? dataxrayUrl + classification.link       : null, 'link')
                syncAttribute(attrErrors, assetId, ids.searchLinkAttrTypeId,  classification.searchLink ? dataxrayUrl + classification.searchLink : null, 'searchLink')
                syncAttribute(attrErrors, assetId, ids.subtypeAttrTypeId,     classification.subtype, 'subtype')
            }

            if (!attrErrors.isEmpty()) {
                loggerApi.warn("Asset '${name}' synced but ${attrErrors.size()} attribute(s) failed: ${attrErrors.join('; ')}")
            }

            touchedAssetIds << assetId
            if (isUpdate) {
                updated++
                loggerApi.info("Updated ${type} asset '${assetName}' [${assetId}]")
            } else {
                created++
                loggerApi.info("Created ${type} asset '${assetName}' [${assetId}]")
            }
        } catch (Exception e) {
            failed++
            def msg = "${name ?: "<unnamed @${idx}>"}: ${e.message}".toString()
            failures << msg
            loggerApi.error("Failed to sync classification ${msg}")
        }
    }

    // --- Retire tagged assets no longer in Data X-Ray ---------------------------

    int retired = 0
    if (classifications.isEmpty()) {
        loggerApi.warn("Data X-Ray returned 0 classifications — skipping retirement step to avoid retiring the whole domain")
    } else {
        def taggedInDomain = fetchTaggedAssetsInDomain(ids.classificationsDomainId, SYNC_TAG)
        def noRetire = (opts.noRetireTypeIds ?: []) as Set
        def toRetire = taggedInDomain.findAll {
            !touchedAssetIds.contains(it.getId()) && it.getStatus()?.getId() != ids.obsoleteStatusId
                && !noRetire.contains(it.getType()?.getId())
        }
        if (!noRetire.isEmpty()) {
            int spared = taggedInDomain.count { !touchedAssetIds.contains(it.getId()) && noRetire.contains(it.getType()?.getId()) }
            if (spared > 0) { loggerApi.warn("Retirement skipped for ${spared} asset(s) whose catalogue could not be fetched completely this run") }
        }
        if (!toRetire.isEmpty()) {
            loggerApi.info("Retiring ${toRetire.size()} asset(s) no longer present in Data X-Ray")
            toRetire.each { asset ->
                try {
                    assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                        .id(asset.getId())
                        .statusId(ids.obsoleteStatusId)
                        .build())
                    retired++
                    loggerApi.info("Retired orphan asset '${asset.getName()}' [${asset.getId()}]")
                } catch (Exception retireEx) {
                    loggerApi.error("Failed to retire orphan '${asset.getName()}' [${asset.getId()}]: ${retireEx.message}")
                }
            }
        }
    }

    loggerApi.info("Data X-Ray sync complete: created=${created}, updated=${updated}, retired=${retired}, skipped=${skipped}, failed=${failed}")
    return [created: created, updated: updated, retired: retired, skipped: skipped, failed: failed, failures: failures]
}

// --- Helpers ---------------------------------------------------------------

def syncAttribute(List errors, UUID assetId, UUID typeId, value, String label) {
    def values = (value == null || (value instanceof String && value.trim().isEmpty())) ? [] : [value]
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .values(values as List<Object>)
            .build())
    } catch (Exception attrEx) {
        errors << "${label}: ${attrEx.message}".toString()
        loggerApi.warn("setAssetAttributes(${label}) on ${assetId} failed: ${attrEx.message}")
    }
}

// Title-case a Data X-Ray resource type (e.g. CLASSIFICATION → "Classification")
// for use as a disambiguating name suffix when no subtype is available.
def typeLabel(String t) {
    if (!t) return ''
    return t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase()
}

// Humanise a Data X-Ray enum-style value (e.g. NAMED_ENTITY → "Named Entity")
// for use as a disambiguating name suffix; returns '' for null/blank input.
def humanize(String s) {
    if (!s?.trim()) return ''
    return s.trim().split('[_\\s]+').collect { w ->
        w.isEmpty() ? w : w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase()
    }.join(' ')
}
