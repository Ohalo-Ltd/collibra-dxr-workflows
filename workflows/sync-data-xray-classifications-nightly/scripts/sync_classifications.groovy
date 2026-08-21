// sync_classifications.groovy
//
// Pulls classifications/annotators/extractors/labels from the Data X-Ray API
// and mirrors them as Collibra assets in the configured classifications
// domain. For each remote item we create one asset and attach description,
// link, search-link and subtype attributes when present.
//
// This workflow is triggered by a timer start event (nightly at 02:00), so it
// runs unattended with no user/initiator. On misconfiguration or a transient
// Data X-Ray outage it logs the reason and ends cleanly rather than throwing:
// a timer-triggered workflow that fails before its first async task is retried
// 3× by Collibra and then permanently disabled until redeployment.
//
// The only configuration variables are the per-instance secrets (Base URL and
// Bearer token), set by an admin on the workflow settings page; until the token
// is supplied the nightly run skips cleanly. All operating-model IDs are fixed
// constants (see below), created by the configure-data-xray-workflows admin
// workflow with exactly those UUIDs.
//
// Sync semantics:
//   – Upsert by Data X-Ray ID. Each Collibra asset carries a "Data X-Ray ID"
//     attribute (type UUID supplied via dataxrayIdAttrTypeId) that stores the
//     remote id; we match on that, so a Data X-Ray-side rename updates the
//     existing asset rather than spawning a duplicate.
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
//
// Process variables produced (for downstream tasks or audit):
//   syncCreatedCount (Integer) – assets newly created
//   syncUpdatedCount (Integer) – existing assets reused (attributes resynced)
//   syncRetiredCount (Integer) – tagged assets retired because they were no longer in Data X-Ray
//   syncSkippedCount (Integer) – classifications skipped (missing name, unknown type, etc.)
//   syncFailedCount  (Integer) – per-classification errors during upsert
//   syncFailures     (String)  – semicolon-joined "<name>: <error>" list, may be empty

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest
import com.collibra.dgc.core.api.dto.instance.asset.AddAssetTagsRequest
import com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest
import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest
import com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest
import groovy.json.JsonSlurper

final String SYNC_TAG = 'dataxray-classification-sync'

// --- Read & validate workflow configuration variables -----------------------

// Only the per-instance secrets are configuration variables. The operating-model
// IDs are fixed across instances (see the constants block below), so they're not
// configurable here.
def requiredConfig = [
    dataxrayUrl      : 'Data X-Ray Base URL',
    dataxrayAuthToken: 'Data X-Ray Auth Token (Bearer)',
]

// Collibra requires a non-empty default for non-readable form properties, so
// variables that have no sensible default (auth token, base URL) ship with a
// sentinel placeholder in the BPMN — any value of the form "<paste … here>"
// is treated as unset.
def isPlaceholder = { String s -> s.startsWith('<paste ') && s.endsWith('>') }

def config  = [:]
def missing = []
requiredConfig.each { varName, label ->
    def raw = execution.getVariable(varName)
    def v = raw == null ? '' : raw.toString().trim()
    if (v.isEmpty() || isPlaceholder(v)) {
        missing << "${label}  (variable: ${varName})"
    } else {
        config[varName] = v
    }
}

if (!missing.isEmpty()) {
    def bullets = '\n  • ' + missing.join('\n  • ')
    def detailMsg = "Skipping Sync Data X-Ray Classification (Nightly) — the following workflow configuration variable(s) are not set:${bullets}\n\nOpen the workflow's settings page and provide a value for each; the next nightly run will pick them up automatically."
    // Timer-triggered: end cleanly rather than throw. A WorkflowException here
    // (before the first async task) would be retried 3× by Collibra and then
    // permanently disable the schedule until redeployment.
    loggerApi.error(detailMsg)
    recordRunSummary(0, 0, 0, 0, 0, "misconfigured: ${missing.size()} configuration variable(s) unset")
    return
}

def dataxrayUrl       = config.dataxrayUrl
def dataxrayAuthToken = config.dataxrayAuthToken

// Canonical operating-model IDs — fixed across all instances. The
// configure-data-xray-workflows admin workflow creates the matching elements
// with exactly these UUIDs, so they're constants here rather than configuration
// variables (which would just be indirection wrapping a constant).
def classificationsDomainId = string2Uuid('019c9fbf-622c-76f4-9dd6-2a9730a11515')

// Map remote type → Collibra asset type UUID.
def assetTypeIdByType = [
    CLASSIFICATION: string2Uuid('01965d43-235d-796b-be49-078f91d7472a'),
    ANNOTATOR     : string2Uuid('01922a69-e7a0-7ac7-a581-c9ba9286ccf1'),
    EXTRACTOR     : string2Uuid('019c9fbd-a91b-7242-9451-79ab632163a3'),
    LABEL         : string2Uuid('019c9fbe-25c3-71b7-90ac-057dd582fa1e'),
]

// Collibra system Description attribute type — same UUID on every instance.
def descriptionAttrTypeId = string2Uuid('00000000-0000-0000-0000-000000003114')
def linkAttrTypeId        = string2Uuid('019c9fc5-aa4c-72af-8918-caa54fe61eba')
def searchLinkAttrTypeId  = string2Uuid('019c9fc5-8ff5-77a7-962d-4b6b05c69254')
def subtypeAttrTypeId     = string2Uuid('019c9fc5-ecc8-759b-9c0b-78547fa315ad')
def dataxrayIdAttrTypeId  = string2Uuid('019e73ae-1aa8-700c-8086-626326822c22')

// Standard statuses (same UUIDs on every instance): orphans are retired to
// Obsolete, reappearing classifications reactivated to Candidate.
def OBSOLETE_STATUS_ID  = string2Uuid('00000000-0000-0000-0000-000000005011')
def CANDIDATE_STATUS_ID = string2Uuid('00000000-0000-0000-0000-000000005008')

// --- Fetch classifications from Data X-Ray ----------------------------------

def classifications
try {
    classifications = fetchClassifications(dataxrayUrl, dataxrayAuthToken, loggerApi)
} catch (Exception fetchEx) {
    // Timer-triggered: a transient Data X-Ray outage must not throw (see above)
    // — log and end cleanly so the schedule survives and retries next night.
    loggerApi.error("Failed to fetch classifications from Data X-Ray: ${fetchEx.message}")
    recordRunSummary(0, 0, 0, 0, 0, "fetch failed: ${fetchEx.message}")
    return
}

loggerApi.info("Data X-Ray returned ${classifications.size()} classification(s); syncing into Collibra")

// --- Index existing assets in the target domain ----------------------------

def existingById = fetchAllAssetsInDomain(classificationsDomainId)
loggerApi.info("Found ${existingById.size()} existing asset(s) in classifications domain")

// Name → asset index for the name-based adoption fallback below. Collibra
// enforces full-name uniqueness per domain (the source of termAlreadyExists),
// so within this domain getName() is effectively a unique key.
def existingByName = [:]
existingById.values().each { a -> existingByName[a.getName()] = a }

// Map Data X-Ray ID → Collibra asset UUID, restricted to assets in this domain.
// findAttributes has no domain filter, so we query globally by type and then
// drop any hits that belong to assets outside the classifications domain.
def assetIdByDataxrayId = fetchAssetIdsByDataxrayId(dataxrayIdAttrTypeId, existingById.keySet())
loggerApi.info("Indexed ${assetIdByDataxrayId.size()} asset(s) with a Data X-Ray ID attribute")

// --- Disambiguate colliding asset names --------------------------------------

// Collibra enforces full-name uniqueness per domain, so two Data X-Ray
// resources sharing a name can't both land here under that bare name — e.g.
// "VAT Number" exists twice: a Named Entity annotator and a Regular Expression
// one. For any name used by more than one resource, derive a distinct Collibra
// name by suffixing each with its subtype (the discriminator shown in the Data
// X-Ray UI), falling back to its type, and finally to a short id fragment if
// those still tie. Names used by a single resource are left untouched.
//
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
            wasRetired = existingById[idMatch]?.getStatus()?.getId() == OBSOLETE_STATUS_ID
        }

        // Fallback: a same-named asset already exists in the domain but has no
        // Data X-Ray ID attribute (e.g. created by an older name-based sync).
        // Adopt it rather than attempting a create Collibra would reject with
        // termAlreadyExists; the ID attribute is stamped below, so the next run
        // matches it by ID directly. Guard on touchedAssetIds so each existing
        // asset is adopted at most once — if Data X-Ray returns two items with
        // the same name (different ids/types), the second falls through to a
        // visible termAlreadyExists failure instead of silently overwriting the
        // first's stamped ID.
        if (assetId == null) {
            def nameMatch = existingByName[assetName]
            if (nameMatch && !touchedAssetIds.contains(nameMatch.getId())) {
                assetId = nameMatch.getId()
                isUpdate = true
                previousName = nameMatch.getName()
                wasRetired = nameMatch.getStatus()?.getId() == OBSOLETE_STATUS_ID
            }
        }

        if (assetId == null) {
            def assetReq = AddAssetRequest.builder()
                .name(assetName)
                .displayName(assetName)
                .domainId(classificationsDomainId)
                .typeId(assetTypeId)
                .build()
            def asset = assetApi.addAsset(assetReq)
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
                def change = ChangeAssetRequest.builder().id(assetId)
                if (needsRename) { change.name(assetName).displayName(assetName) }
                if (wasRetired)  { change.statusId(CANDIDATE_STATUS_ID) }
                assetApi.changeAsset(change.build())
                if (needsRename) { loggerApi.info("Renamed asset [${assetId}]: '${previousName}' → '${assetName}'") }
                if (wasRetired)  { loggerApi.info("Reactivated previously retired asset '${assetName}' [${assetId}]") }
            } catch (Exception renameEx) {
                loggerApi.warn("Failed to rename/reactivate '${previousName}' → '${assetName}' on ${assetId}: ${renameEx.message}")
            }
        }

        // Tag the asset (idempotent — addAssetTags is a no-op for tags already present)
        try {
            assetApi.addAssetTags(AddAssetTagsRequest.builder()
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
        syncAttribute(attrErrors, assetId, dataxrayIdAttrTypeId,  dataxrayId, 'dataxrayId')
        syncAttribute(attrErrors, assetId, descriptionAttrTypeId, classification.description, 'description')
        syncAttribute(attrErrors, assetId, linkAttrTypeId,        classification.link       ? dataxrayUrl + classification.link       : null, 'link')
        syncAttribute(attrErrors, assetId, searchLinkAttrTypeId,  classification.searchLink ? dataxrayUrl + classification.searchLink : null, 'searchLink')
        syncAttribute(attrErrors, assetId, subtypeAttrTypeId,     classification.subtype, 'subtype')

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
        def msg = "${name ?: "<unnamed @${idx}>"}: ${e.message}"
        failures << msg
        loggerApi.error("Failed to sync classification ${msg}")
    }
}

// --- Retire tagged assets no longer in Data X-Ray ---------------------------

// Retired, never deleted: saved queries and imported file assets hold
// relations to these classifications, and history (comments, workflow tasks)
// must survive. A reappearing classification is reactivated by the upsert
// pass above.
int retired = 0
if (classifications.isEmpty()) {
    loggerApi.warn("Data X-Ray returned 0 classifications — skipping retirement step to avoid retiring the whole domain")
} else {
    def taggedInDomain = fetchTaggedAssetsInDomain(classificationsDomainId, SYNC_TAG)
    def toRetire = taggedInDomain.findAll {
        !touchedAssetIds.contains(it.getId()) && it.getStatus()?.getId() != OBSOLETE_STATUS_ID
    }
    if (!toRetire.isEmpty()) {
        loggerApi.info("Retiring ${toRetire.size()} asset(s) no longer present in Data X-Ray")
        toRetire.each { asset ->
            try {
                assetApi.changeAsset(ChangeAssetRequest.builder()
                    .id(asset.getId())
                    .statusId(OBSOLETE_STATUS_ID)
                    .build())
                retired++
                loggerApi.info("Retired orphan asset '${asset.getName()}' [${asset.getId()}]")
            } catch (Exception retireEx) {
                loggerApi.error("Failed to retire orphan '${asset.getName()}' [${asset.getId()}]: ${retireEx.message}")
            }
        }
    }
}

// --- Report -----------------------------------------------------------------

recordRunSummary(created, updated, retired, skipped, failed, failures.join('; '))

loggerApi.info("Data X-Ray sync complete: created=${created}, updated=${updated}, retired=${retired}, skipped=${skipped}, failed=${failed}")

// If nothing was created or updated and at least one item failed, log it as an
// error for dgc.log. Timer-triggered: don't throw — a thrown WorkflowException
// before the first async task is retried 3× and then disables the schedule.
if (created == 0 && updated == 0 && failed > 0) {
    loggerApi.error("Data X-Ray sync run failed: all ${failed} classification(s) failed to sync. First error: ${failures[0]}")
}

// --- Helpers ---------------------------------------------------------------

// Persist the run outcome to process variables for audit / downstream tasks.
def recordRunSummary(int created, int updated, int retired, int skipped, int failed, String failuresJoined) {
    execution.setVariable('syncCreatedCount', created)
    execution.setVariable('syncUpdatedCount', updated)
    execution.setVariable('syncRetiredCount', retired)
    execution.setVariable('syncSkippedCount', skipped)
    execution.setVariable('syncFailedCount',  failed)
    execution.setVariable('syncFailures',     failuresJoined)
}

def syncAttribute(List errors, UUID assetId, UUID typeId, value, String label) {
    def values = (value == null || (value instanceof String && value.trim().isEmpty())) ? [] : [value]
    try {
        def req = SetAssetAttributesRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .values(values as List<Object>)
            .build()
        assetApi.setAssetAttributes(req)
    } catch (Exception attrEx) {
        errors << "${label}: ${attrEx.message}"
        loggerApi.warn("setAssetAttributes(${label}) on ${assetId} failed: ${attrEx.message}")
    }
}

def fetchAllAssetsInDomain(UUID domainId) {
    def byId = [:]
    def cursor = ''
    while (true) {
        def req = FindAssetsRequest.builder()
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = assetApi.findAssets(req)
        page.getResults().each { asset ->
            byId[asset.getId()] = asset
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return byId
}

def fetchAssetIdsByDataxrayId(UUID dataxrayIdAttrTypeId, Set<UUID> assetsInDomain) {
    def assetIdByDataxrayId = [:]
    def cursor = ''
    while (true) {
        def req = FindAttributesRequest.builder()
            .typeIds([dataxrayIdAttrTypeId])
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = attributeApi.findAttributes(req)
        page.getResults().each { attr ->
            def aid = attr.getAsset()?.getId()
            def val = attr.getValue()
            if (aid && val && assetsInDomain.contains(aid)) {
                assetIdByDataxrayId[val.toString()] = aid
            }
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return assetIdByDataxrayId
}

def fetchTaggedAssetsInDomain(UUID domainId, String tagName) {
    def results = []
    def cursor = ''
    while (true) {
        def req = FindAssetsRequest.builder()
            .domainId(domainId)
            .tagNames([tagName])
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = assetApi.findAssets(req)
        results.addAll(page.getResults())
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return results
}

def fetchClassifications(String baseUrl, String authToken, loggerApi) {
    def endpoint = baseUrl + '/api/v1/classifications'
    def conn = (HttpURLConnection) new URL(endpoint).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${authToken}")
    conn.setRequestProperty('Content-Type', 'application/json')
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(60_000)

    def code = conn.getResponseCode()
    if (code != 200) {
        def errBody = ''
        try {
            errBody = conn.getErrorStream()?.getText('UTF-8') ?: ''
        } catch (Exception ignored) {
            // best-effort
        }
        throw new RuntimeException("Data X-Ray API returned HTTP ${code}: ${truncate(errBody, 500)}")
    }

    def body = conn.getInputStream().getText('UTF-8')
    def parsed
    try {
        parsed = new JsonSlurper().parseText(body)
    } catch (Exception parseEx) {
        throw new RuntimeException("Data X-Ray API returned non-JSON body: ${parseEx.message}")
    }

    def data = parsed?.data
    if (!(data instanceof List)) {
        throw new RuntimeException("Data X-Ray API response missing 'data' array; got keys: ${parsed?.keySet()}")
    }
    return data
}

def truncate(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
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
