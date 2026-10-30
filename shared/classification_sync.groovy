// classification_sync.groovy — mirroring the DataXray classification
// catalogue into Collibra (new assets in the DataXray Classifications domain;
// existing ones updated wherever they live).
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// The transport that fetches the catalogue is the caller's concern; this file
// takes the parsed `data` list and does everything Collibra-side.
//
// Sync semantics:
//   – Upsert by DataXray ID. Each Collibra asset carries a "DataXray ID"
//     attribute that stores the remote id; we match on that, so a DataXray-side
//     rename updates the existing asset rather than spawning a duplicate.
//   – No name fallback: an item without an ID match is always a NEW asset,
//     even when a same-named asset exists. A label deleted in DataXray and
//     re-created under the same name gets a new DataXray ID, so it must not
//     revive the old (retired) asset with its history and links.
//   – Freeing a name: Collibra enforces full-name uniqueness per domain, so a
//     create or rename onto a name held by an asset whose DataXray ID is no
//     longer in the catalogue (retired, or about to be retired by this run)
//     first renames that asset to "<name> (retired <id fragment>)". A name held
//     by a current item or by an asset without a DataXray ID (added by hand)
//     is never taken over — the item fails with a message naming the clash.
//   – Name disambiguation: Collibra enforces full-name uniqueness per domain.
//     When one name is used by more than one DataXray resource (e.g. two
//     annotators both named "VAT Number", one Named Entity and one Regular
//     Expression), each such asset's Collibra name is suffixed to keep them
//     distinct — "VAT Number (Named Entity)", "VAT Number (Regular Expression)".
//     The suffix is the subtype (the discriminator shown in the DataXray UI),
//     falling back to type, then to a short id fragment if those still tie.
//     Names used by a single resource are left untouched.
//   – When matched by ID, if the DataXray name has diverged from the Collibra
//     asset name we rename the Collibra asset to match.
//   – Every synced asset is tagged with 'dataxray-classification-sync'.
//     Classification assets (by type, wherever they live) that carry this tag
//     but are no longer in the current DataXray response are RETIRED (status
//     Obsolete) — never deleted. Saved
//     search queries and imported file assets hold relations to these
//     classifications, and a rerun must be able to say "this criterion was
//     deleted in DataXray" rather than find a dangling reference. If the
//     classification reappears in DataXray, the asset is reactivated
//     (status Candidate). Untagged assets are never touched, so anything
//     added by hand is safe.
//   – Lookups are by asset TYPE over the whole instance, not by domain, so a
//     classification moved to another domain/community is matched by its
//     DataXray ID and updated where it is: no duplicate in the Classifications
//     domain, and its file links survive (TRC R4/R6). New assets are still
//     created in the Classifications domain.
//   – If DataXray returns zero classifications, the retirement step is
//     skipped to avoid a transient/broken response retiring all assets.

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}

// Sync the catalogue. `dataxrayUrl` (no trailing slash) prefixes the relative
// link/searchLink paths DataXray returns. Returns
// [created:, updated:, retired:, skipped:, failed:, failures: [String]].
// opts (optional):
//   indexIdAttrTypeId — when set, an item's `indexId` (DataXray's numeric
//                       search-index id) is stamped on the asset (Edge edition).
//   noRetireTypeIds   — asset type ids that must NOT be retired this run (the
//                       caller saw an incomplete list for them; Edge fallback).
//   Items may carry `partial: true`: the item is known to exist in DataXray
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

    // --- Index existing classification assets, wherever they live ---------------

    // By asset type over the whole instance, not by domain: a classification
    // someone moved to another domain or community is still matched by its
    // DataXray ID and updated in place — never duplicated in the Classifications
    // domain, never retired for being out of sight, and the file rows' refs
    // keep resolving to it so its file links survive the move.
    def existingById = fetchAllClassificationAssets(ids)
    int movedOut = existingById.values().count { it.getDomain()?.getId() != ids.classificationsDomainId }
    loggerApi.info("Found ${existingById.size()} existing classification asset(s)${movedOut > 0 ? " (${movedOut} outside the DataXray Classifications domain)" : ''}")

    // Name → asset index, for detecting name clashes (freeName below). Collibra
    // enforces full-name uniqueness per domain (the source of termAlreadyExists),
    // and new assets are created in the Classifications domain, so only the
    // assets in that domain take part.
    def existingByName = [:]
    existingById.values().each { a ->
        if (a.getDomain()?.getId() == ids.classificationsDomainId) { existingByName[a.getName()] = a }
    }

    // Map DataXray ID → Collibra asset UUID, restricted to classification assets.
    def assetIdByDataxrayId = fetchAssetIdsByDataxrayId(ids.dataxrayIdAttrTypeId, existingById.keySet())
    loggerApi.info("Indexed ${assetIdByDataxrayId.size()} asset(s) with a DataXray ID attribute")
    def dataxrayIdByAssetId = [:]
    assetIdByDataxrayId.each { dxrId, aid -> dataxrayIdByAssetId[aid] = dxrId }
    def currentDataxrayIds = classifications.findAll { it?.id != null }.collect { it.id.toString().trim() } as Set

    // Make `wanted` available for `forAssetId` (null = a create). Returns true
    // when the name is free (or already this asset's). A holder whose DataXray
    // ID has left the catalogue is renamed out of the way; any other holder is
    // left alone and the caller reports the clash.
    def freeName = { String wanted, UUID forAssetId ->
        def holder = existingByName[wanted]
        if (holder == null || holder.getId() == forAssetId) { return true }
        def holderDxrId = dataxrayIdByAssetId[holder.getId()]
        if (!holderDxrId || currentDataxrayIds.contains(holderDxrId)) { return false }
        def retiredName = "${wanted} (retired ${holderDxrId.replaceAll('-', '').take(8)})".toString()
        assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
            .id(holder.getId())
            .name(retiredName)
            .displayName(retiredName)
            .build())
        existingByName.remove(wanted)
        existingByName[retiredName] = holder
        loggerApi.info("Renamed asset [${holder.getId()}] '${wanted}' → '${retiredName}': its DataXray ID ${holderDxrId} is no longer in the catalogue and the name is needed")
        return true
    }

    // --- Disambiguate colliding asset names --------------------------------------

    // Keyed by DataXray id so the loop can look up each item's resolved name.
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
                loggerApi.warn("Skipping '${name}': missing DataXray 'id'")
                return
            }

            def type = classification.type as String
            def assetTypeId = assetTypeIdByType[type]
            if (!assetTypeId) {
                skipped++
                if (type == 'ANNOTATOR_DOMAIN') {
                    // Data categories group annotators in DataXray; they are not mirrored (the annotators are).
                    loggerApi.info("Skipping data category '${name}' (data categories are not mirrored to Collibra)")
                } else {
                    loggerApi.warn("Skipping '${name}': unknown type '${type}'")
                }
                return
            }

            // The Collibra asset name. Disambiguated (see disambiguatedName) only
            // when this name is shared by more than one DataXray resource, so
            // collisions get distinct names rather than failing a create with
            // termAlreadyExists; otherwise the bare DataXray name is used.
            def assetName = (dataxrayId && disambiguatedName.containsKey(dataxrayId)) ? disambiguatedName[dataxrayId] : name.toString()

            // Resolution: match by DataXray ID, else create a new asset.
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

            if (assetId == null) {
                if (!freeName(assetName, null)) {
                    throw new IllegalStateException("the name '${assetName}' is already used by asset ${existingByName[assetName].getId()}, which is not a retired DataXray classification".toString())
                }
                def asset = assetApi.addAsset(com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest.builder()
                    .name(assetName)
                    .displayName(assetName)
                    .domainId(ids.classificationsDomainId)
                    .typeId(assetTypeId)
                    .build())
                assetId = asset.getId()
                existingByName[assetName] = asset
            }

            // Rename if the desired (possibly type-suffixed) name has diverged from
            // what's in Collibra — covers both DataXray-side renames and an asset
            // newly becoming/ceasing to be a cross-type collision. Reactivate in
            // the same change if a previous sync had retired this classification
            // and it has now reappeared in DataXray.
            def needsRename = isUpdate && previousName != null && previousName != assetName
            if (needsRename || wasRetired) {
                try {
                    if (needsRename && freeName(assetName, assetId)) {
                        existingByName.remove(previousName)
                        existingByName[assetName] = existingById[assetId]
                    }
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
            // both fresh creates and updates. Stamping the DataXray ID here keys
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

    // --- Retire tagged assets no longer in DataXray ---------------------------

    int retired = 0
    if (classifications.isEmpty()) {
        loggerApi.warn("DataXray returned 0 classifications — skipping retirement step to avoid retiring the whole domain")
    } else {
        def taggedInDomain = fetchTaggedClassificationAssets(ids, SYNC_TAG)
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
            loggerApi.info("Retiring ${toRetire.size()} asset(s) no longer present in DataXray")
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

    loggerApi.info("DataXray sync complete: created=${created}, updated=${updated}, retired=${retired}, skipped=${skipped}, failed=${failed}")
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

// Title-case a DataXray resource type (e.g. CLASSIFICATION → "Classification")
// for use as a disambiguating name suffix when no subtype is available.
def typeLabel(String t) {
    if (!t) return ''
    return t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase()
}

// Humanise a DataXray enum-style value (e.g. NAMED_ENTITY → "Named Entity")
// for use as a disambiguating name suffix; returns '' for null/blank input.
def humanize(String s) {
    if (!s?.trim()) return ''
    return s.trim().split('[_\\s]+').collect { w ->
        w.isEmpty() ? w : w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase()
    }.join(' ')
}
