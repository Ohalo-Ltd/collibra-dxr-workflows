// sync_classifications.groovy
//
// Pulls classifications/annotators/extractors/labels from the Ohalo Data X-Ray
// API and mirrors them as Collibra assets in the configured classifications
// domain. For each remote item we create one asset and attach description,
// link, search-link and subtype attributes when present.
//
// All configuration is read from workflow configuration variables (form
// properties on the start event). An admin sets/edits them on the workflow
// settings page in Collibra; defaults shipped in the BPMN make this work
// out-of-the-box for the demo instance, except for ohaloAuthToken which has
// no default and must be supplied before the workflow can run.
//
// Sync semantics:
//   – Upsert by name within the configured classifications domain.
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
import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
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
    def detailMsg = "Cannot run Sync Ohalo Classifications — the following workflow configuration variable(s) are not set:${bullets}\n\nOpen the workflow's settings page and provide a value for each, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('Ohalo sync misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
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

// --- Fetch classifications from Ohalo ---------------------------------------

def classifications
try {
    classifications = fetchClassifications(ohaloUrl, ohaloAuthToken, loggerApi)
} catch (Exception fetchEx) {
    loggerApi.error("Failed to fetch classifications from Ohalo: ${fetchEx.message}")
    def wf = new WorkflowException("Ohalo classification fetch failed: ${fetchEx.message}", fetchEx)
    wf.setTitleMessage('Ohalo sync failed')
    wf.setUserMessage("Could not fetch classifications from Ohalo: ${fetchEx.message}")
    throw wf
}

loggerApi.info("Ohalo returned ${classifications.size()} classification(s); syncing into Collibra")

// --- Index existing assets in the target domain ----------------------------

def existingByName = fetchAllAssetsInDomain(classificationsDomainId)
loggerApi.info("Found ${existingByName.size()} existing asset(s) in classifications domain")

// --- Upsert each classification --------------------------------------------

int created = 0
int updated = 0
int failed  = 0
int skipped = 0
def failures = []
def touchedAssetIds = [] as Set

classifications.eachWithIndex { classification, idx ->
    def name = classification?.name
    try {
        if (!name) {
            skipped++
            loggerApi.warn("Skipping classification at index ${idx}: missing 'name'")
            return
        }

        def type = classification.type as String
        def assetTypeId = assetTypeIdByType[type]
        if (!assetTypeId) {
            skipped++
            loggerApi.warn("Skipping '${name}': unknown type '${type}'")
            return
        }

        def existing = existingByName[name]
        UUID assetId
        boolean isUpdate
        if (existing) {
            assetId = existing.getId()
            isUpdate = true
        } else {
            def assetReq = AddAssetRequest.builder()
                .name(name)
                .displayName(name)
                .domainId(classificationsDomainId)
                .typeId(assetTypeId)
                .build()
            def asset = assetApi.addAsset(assetReq)
            assetId = asset.getId()
            isUpdate = false
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
        // both fresh creates and updates.
        def attrErrors = []
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

execution.setVariable('syncCreatedCount', created)
execution.setVariable('syncUpdatedCount', updated)
execution.setVariable('syncDeletedCount', deleted)
execution.setVariable('syncSkippedCount', skipped)
execution.setVariable('syncFailedCount',  failed)
execution.setVariable('syncFailures',     failures.join('; '))

loggerApi.info("Ohalo sync complete: created=${created}, updated=${updated}, deleted=${deleted}, skipped=${skipped}, failed=${failed}")

// If nothing was created or updated and at least one item failed, surface the
// run as a failure in the Collibra UI.
if (created == 0 && updated == 0 && failed > 0) {
    def wf = new WorkflowException("All ${failed} classification(s) failed to sync. First error: ${failures[0]}")
    wf.setTitleMessage('Ohalo sync failed')
    wf.setUserMessage("All ${failed} classification(s) failed to sync. First error: ${failures[0]}")
    throw wf
}

// --- Helpers ---------------------------------------------------------------

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
    def cursor = ''
    while (true) {
        def req = FindAssetsRequest.builder()
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build()
        def page = assetApi.findAssets(req)
        page.getResults().each { asset -> byName[asset.getName()] = asset }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return byName
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
