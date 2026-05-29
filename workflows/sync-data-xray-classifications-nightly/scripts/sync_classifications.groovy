// sync_classifications.groovy
//
// Pulls classifications/annotators/extractors/labels from the Ohalo Data X-Ray
// API and mirrors them as Collibra assets in the configured classifications
// domain. For each remote item we create one asset and attach description,
// link, search-link and subtype attributes when present.
//
// This workflow is triggered by a timer start event (nightly at 02:00), so it
// runs unattended with no user/initiator. On misconfiguration or a transient
// Ohalo outage it logs the reason and ends cleanly rather than throwing: a
// timer-triggered workflow that fails before its first async task is retried
// 3× by Collibra and then permanently disabled until redeployment.
//
// All configuration is read from workflow configuration variables (form
// properties on the start event). An admin sets/edits them on the workflow
// settings page in Collibra; defaults shipped in the BPMN make this work
// out-of-the-box for the demo instance, except for ohaloAuthToken which has
// no default and must be supplied before the workflow will do anything.
//
// Sync semantics:
//   – Upsert by Ohalo ID. Each Collibra asset carries an "Ohalo ID" attribute
//     (type UUID supplied via ohaloIdAttrTypeId) that stores the remote id; we
//     match on that, so an Ohalo-side rename updates the existing asset rather
//     than spawning a duplicate.
//   – Legacy fallback: if no Ohalo-ID match is found, we look for an asset in
//     the domain with the same name (assets created by the old name-based
//     sync) and adopt it, stamping the Ohalo ID onto it. After one sync run,
//     every managed asset is keyed by ID; the name fallback effectively
//     migrates a legacy domain in place without losing history or relations.
//   – When matched by ID, if the Ohalo name has diverged from the Collibra
//     asset name we rename the Collibra asset to match.
//   – Every synced asset is tagged with 'ohalo-classification-sync'. Assets in
//     the domain that carry this tag but are no longer in the current Ohalo
//     response are deleted. Untagged assets in the domain are never touched,
//     so anything added by hand is safe.
//   – If Ohalo returns zero classifications, the deletion step is skipped to
//     avoid a transient/broken response wiping all assets.
//
// Process variables produced (for downstream tasks or audit):
//   syncCreatedCount (Integer) – assets newly created
//   syncUpdatedCount (Integer) – existing assets reused (attributes resynced)
//   syncDeletedCount (Integer) – tagged assets removed because they were no longer in Ohalo
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

final String SYNC_TAG = 'ohalo-classification-sync'

// --- Read & validate workflow configuration variables -----------------------

// Required configuration variables: variable name → human label shown in errors
def requiredConfig = [
    ohaloUrl                 : 'Ohalo Base URL',
    ohaloAuthToken           : 'Ohalo Auth Token (Bearer)',
    classificationsDomainId  : 'Classifications Domain ID',
    classificationAssetTypeId: 'Asset Type ID: Classification',
    annotatorAssetTypeId     : 'Asset Type ID: Annotator',
    extractorAssetTypeId     : 'Asset Type ID: Extractor',
    labelAssetTypeId         : 'Asset Type ID: Label',
    linkAttrTypeId           : 'Attribute Type ID: Link',
    searchLinkAttrTypeId     : 'Attribute Type ID: Search Link',
    subtypeAttrTypeId        : 'Attribute Type ID: Subtype',
    ohaloIdAttrTypeId        : 'Attribute Type ID: Ohalo ID',
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

def ohaloUrl       = config.ohaloUrl
def ohaloAuthToken = config.ohaloAuthToken

def classificationsDomainId = string2Uuid(config.classificationsDomainId)

// Map remote type → Collibra asset type UUID
def assetTypeIdByType = [
    CLASSIFICATION: string2Uuid(config.classificationAssetTypeId),
    ANNOTATOR     : string2Uuid(config.annotatorAssetTypeId),
    EXTRACTOR     : string2Uuid(config.extractorAssetTypeId),
    LABEL         : string2Uuid(config.labelAssetTypeId),
]

// Collibra system Description attribute type — same UUID on every Collibra
// instance, so kept as a constant rather than a config variable.
def descriptionAttrTypeId = string2Uuid('00000000-0000-0000-0000-000000003114')
def linkAttrTypeId        = string2Uuid(config.linkAttrTypeId)
def searchLinkAttrTypeId  = string2Uuid(config.searchLinkAttrTypeId)
def subtypeAttrTypeId     = string2Uuid(config.subtypeAttrTypeId)
def ohaloIdAttrTypeId     = string2Uuid(config.ohaloIdAttrTypeId)

// --- Fetch classifications from Ohalo ---------------------------------------

def classifications
try {
    classifications = fetchClassifications(ohaloUrl, ohaloAuthToken, loggerApi)
} catch (Exception fetchEx) {
    // Timer-triggered: a transient Ohalo outage must not throw (see above) —
    // log and end cleanly so the schedule survives and retries next night.
    loggerApi.error("Failed to fetch classifications from Ohalo: ${fetchEx.message}")
    recordRunSummary(0, 0, 0, 0, 0, "fetch failed: ${fetchEx.message}")
    return
}

loggerApi.info("Ohalo returned ${classifications.size()} classification(s); syncing into Collibra")

// --- Index existing assets in the target domain ----------------------------

def existing = fetchAllAssetsInDomain(classificationsDomainId)
def existingByName = existing.byName
def existingById   = existing.byId
loggerApi.info("Found ${existingByName.size()} existing asset(s) in classifications domain")

// Map Ohalo ID → Collibra asset UUID, restricted to assets in this domain.
// findAttributes has no domain filter, so we query globally by type and then
// drop any hits that belong to assets outside the classifications domain.
def assetIdByOhaloId = fetchAssetIdsByOhaloId(ohaloIdAttrTypeId, existingById.keySet())
loggerApi.info("Indexed ${assetIdByOhaloId.size()} asset(s) with an Ohalo ID attribute")

// --- Upsert each classification --------------------------------------------

int created = 0
int updated = 0
int failed  = 0
int skipped = 0
def failures = []
def touchedAssetIds = [] as Set

classifications.eachWithIndex { classification, idx ->
    def name = classification?.name
    def ohaloId = classification?.id == null ? null : classification.id.toString().trim()
    try {
        if (!name) {
            skipped++
            loggerApi.warn("Skipping classification at index ${idx}: missing 'name'")
            return
        }
        if (!ohaloId) {
            skipped++
            loggerApi.warn("Skipping '${name}': missing Ohalo 'id'")
            return
        }

        def type = classification.type as String
        def assetTypeId = assetTypeIdByType[type]
        if (!assetTypeId) {
            skipped++
            loggerApi.warn("Skipping '${name}': unknown type '${type}'")
            return
        }

        // Resolution order: Ohalo ID → legacy name match → create new.
        UUID assetId = null
        boolean isUpdate = false
        String previousName = null

        def idMatch = assetIdByOhaloId[ohaloId]
        if (idMatch) {
            assetId = idMatch
            isUpdate = true
            previousName = existingById[idMatch]?.getName()
        }

        if (assetId == null) {
            def nameMatch = existingByName[name]
            if (nameMatch) {
                assetId = nameMatch.getId()
                isUpdate = true
                previousName = nameMatch.getName()
                loggerApi.info("Adopting legacy asset '${name}' [${assetId}] by name; stamping Ohalo ID ${ohaloId}")
            }
        }

        if (assetId == null) {
            def assetReq = AddAssetRequest.builder()
                .name(name)
                .displayName(name)
                .domainId(classificationsDomainId)
                .typeId(assetTypeId)
                .build()
            def asset = assetApi.addAsset(assetReq)
            assetId = asset.getId()
        }

        // Rename if the Ohalo name has diverged from what's in Collibra.
        if (isUpdate && previousName != null && previousName != name) {
            try {
                assetApi.changeAsset(ChangeAssetRequest.builder()
                    .id(assetId)
                    .name(name)
                    .displayName(name)
                    .build())
                loggerApi.info("Renamed asset [${assetId}]: '${previousName}' → '${name}'")
            } catch (Exception renameEx) {
                loggerApi.warn("Failed to rename '${previousName}' → '${name}' on ${assetId}: ${renameEx.message}")
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
        // both fresh creates and updates. Stamping the Ohalo ID here covers
        // first-time-created assets and the legacy name-fallback path.
        def attrErrors = []
        syncAttribute(attrErrors, assetId, ohaloIdAttrTypeId,     ohaloId, 'ohaloId')
        syncAttribute(attrErrors, assetId, descriptionAttrTypeId, classification.description, 'description')
        syncAttribute(attrErrors, assetId, linkAttrTypeId,        classification.link       ? ohaloUrl + classification.link       : null, 'link')
        syncAttribute(attrErrors, assetId, searchLinkAttrTypeId,  classification.searchLink ? ohaloUrl + classification.searchLink : null, 'searchLink')
        syncAttribute(attrErrors, assetId, subtypeAttrTypeId,     classification.subtype, 'subtype')

        if (!attrErrors.isEmpty()) {
            loggerApi.warn("Asset '${name}' synced but ${attrErrors.size()} attribute(s) failed: ${attrErrors.join('; ')}")
        }

        touchedAssetIds << assetId
        if (isUpdate) {
            updated++
            loggerApi.info("Updated ${type} asset '${name}' [${assetId}]")
        } else {
            created++
            loggerApi.info("Created ${type} asset '${name}' [${assetId}]")
        }
    } catch (Exception e) {
        failed++
        def msg = "${name ?: "<unnamed @${idx}>"}: ${e.message}"
        failures << msg
        loggerApi.error("Failed to sync classification ${msg}")
    }
}

// --- Delete tagged assets no longer in Ohalo --------------------------------

int deleted = 0
if (classifications.isEmpty()) {
    loggerApi.warn("Ohalo returned 0 classifications — skipping deletion step to avoid wiping the domain")
} else {
    def taggedInDomain = fetchTaggedAssetsInDomain(classificationsDomainId, SYNC_TAG)
    def toDelete = taggedInDomain.findAll { !touchedAssetIds.contains(it.getId()) }
    if (!toDelete.isEmpty()) {
        loggerApi.info("Removing ${toDelete.size()} asset(s) no longer present in Ohalo")
        toDelete.each { asset ->
            try {
                assetApi.removeAsset(asset.getId())
                deleted++
                loggerApi.info("Deleted orphan asset '${asset.getName()}' [${asset.getId()}]")
            } catch (Exception delEx) {
                loggerApi.error("Failed to delete orphan '${asset.getName()}' [${asset.getId()}]: ${delEx.message}")
            }
        }
    }
}

// --- Report -----------------------------------------------------------------

recordRunSummary(created, updated, deleted, skipped, failed, failures.join('; '))

loggerApi.info("Ohalo sync complete: created=${created}, updated=${updated}, deleted=${deleted}, skipped=${skipped}, failed=${failed}")

// If nothing was created or updated and at least one item failed, log it as an
// error for dgc.log. Timer-triggered: don't throw — a thrown WorkflowException
// before the first async task is retried 3× and then disables the schedule.
if (created == 0 && updated == 0 && failed > 0) {
    loggerApi.error("Ohalo sync run failed: all ${failed} classification(s) failed to sync. First error: ${failures[0]}")
}

// --- Helpers ---------------------------------------------------------------

// Persist the run outcome to process variables for audit / downstream tasks.
def recordRunSummary(int created, int updated, int deleted, int skipped, int failed, String failuresJoined) {
    execution.setVariable('syncCreatedCount', created)
    execution.setVariable('syncUpdatedCount', updated)
    execution.setVariable('syncDeletedCount', deleted)
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
    def byName = [:]
    def byId   = [:]
    def cursor = ''
    while (true) {
        def req = FindAssetsRequest.builder()
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = assetApi.findAssets(req)
        page.getResults().each { asset ->
            byName[asset.getName()] = asset
            byId[asset.getId()]     = asset
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return [byName: byName, byId: byId]
}

def fetchAssetIdsByOhaloId(UUID ohaloIdAttrTypeId, Set<UUID> assetsInDomain) {
    def assetIdByOhaloId = [:]
    def cursor = ''
    while (true) {
        def req = FindAttributesRequest.builder()
            .typeIds([ohaloIdAttrTypeId])
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = attributeApi.findAttributes(req)
        page.getResults().each { attr ->
            def aid = attr.getAsset()?.getId()
            def val = attr.getValue()
            if (aid && val && assetsInDomain.contains(aid)) {
                assetIdByOhaloId[val.toString()] = aid
            }
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return assetIdByOhaloId
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
        throw new RuntimeException("Ohalo API returned HTTP ${code}: ${truncate(errBody, 500)}")
    }

    def body = conn.getInputStream().getText('UTF-8')
    def parsed
    try {
        parsed = new JsonSlurper().parseText(body)
    } catch (Exception parseEx) {
        throw new RuntimeException("Ohalo API returned non-JSON body: ${parseEx.message}")
    }

    def data = parsed?.data
    if (!(data instanceof List)) {
        throw new RuntimeException("Ohalo API response missing 'data' array; got keys: ${parsed?.keySet()}")
    }
    return data
}

def truncate(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
}
