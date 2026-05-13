// sync_classifications.groovy
//
// Pulls classifications/annotators/extractors/labels from the Ohalo Data X-Ray
// API and mirrors them as Collibra assets in the configured classifications
// domain. For each remote item we create one asset and attach description,
// link, search-link and subtype attributes when present.
//
// Hardcoded configuration is intentional for the first iteration — the next
// pass will lift these into form variables / process-deployment variables.
//
// Process variables produced (for downstream tasks or audit):
//   syncCreatedCount (Integer) – assets successfully created
//   syncFailedCount  (Integer) – classifications that errored out
//   syncSkippedCount (Integer) – classifications skipped (unknown type, etc.)
//   syncFailures     (String)  – semicolon-joined "<name>: <error>" list, may be empty

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest
import com.collibra.dgc.core.api.dto.instance.attribute.AddAttributeRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonSlurper

// --- Configuration (hardcoded for now) --------------------------------------

def ohaloUrl       = 'https://collibra-demo-integration.dataxray.io'
def ohaloAuthToken = 'REDACTED-LEAKED-TOKEN-REVOKED'

def classificationsDomainId = string2Uuid('019c9fbf-622c-76f4-9dd6-2a9730a11515')

// Map remote type → Collibra asset type UUID
def assetTypeIdByType = [
    CLASSIFICATION: string2Uuid('01965d43-235d-796b-be49-078f91d7472a'),
    ANNOTATOR     : string2Uuid('01922a69-e7a0-7ac7-a581-c9ba9286ccf1'),
    EXTRACTOR     : string2Uuid('019c9fbd-a91b-7242-9451-79ab632163a3'),
    LABEL         : string2Uuid('019c9fbe-25c3-71b7-90ac-057dd582fa1e'),
]

def descriptionAttrTypeId = string2Uuid('00000000-0000-0000-0000-000000003114')
def linkAttrTypeId        = string2Uuid('019c9fc5-aa4c-72af-8918-caa54fe61eba')
def searchLinkAttrTypeId  = string2Uuid('019c9fc5-8ff5-77a7-962d-4b6b05c69254')
def subtypeAttrTypeId     = string2Uuid('019c9fc5-ecc8-759b-9c0b-78547fa315ad')

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

// --- Sync each classification ----------------------------------------------

int created = 0
int failed  = 0
int skipped = 0
def failures = []

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

        def assetReq = AddAssetRequest.builder()
            .name(name)
            .displayName(name)
            .domainId(classificationsDomainId)
            .typeId(assetTypeId)
            .build()
        def asset = assetApi.addAsset(assetReq)
        def assetId = asset.getId()
        loggerApi.info("Created ${type} asset '${name}' [${assetId}]")

        // Attributes – each is independent; a single attribute failure should
        // not abandon the others on the same asset, but should surface in the
        // per-classification error if every attempt fails.
        def attrErrors = []
        addAttr(attrErrors, assetId, descriptionAttrTypeId, classification.description, 'description')
        if (classification.link) {
            addAttr(attrErrors, assetId, linkAttrTypeId, ohaloUrl + classification.link, 'link')
        }
        if (classification.searchLink) {
            addAttr(attrErrors, assetId, searchLinkAttrTypeId, ohaloUrl + classification.searchLink, 'searchLink')
        }
        addAttr(attrErrors, assetId, subtypeAttrTypeId, classification.subtype, 'subtype')

        if (!attrErrors.isEmpty()) {
            loggerApi.warn("Asset '${name}' created but ${attrErrors.size()} attribute(s) failed: ${attrErrors.join('; ')}")
        }

        created++
    } catch (Exception e) {
        failed++
        def msg = "${name ?: "<unnamed @${idx}>"}: ${e.message}"
        failures << msg
        loggerApi.error("Failed to sync classification ${msg}")
    }
}

// --- Report -----------------------------------------------------------------

execution.setVariable('syncCreatedCount', created)
execution.setVariable('syncFailedCount',  failed)
execution.setVariable('syncSkippedCount', skipped)
execution.setVariable('syncFailures',     failures.join('; '))

loggerApi.info("Ohalo sync complete: created=${created}, failed=${failed}, skipped=${skipped}")

// If nothing succeeded and at least one classification was attempted, treat
// the whole run as a failure so the workflow surfaces in the Collibra UI.
if (created == 0 && failed > 0) {
    def wf = new WorkflowException("All ${failed} classification(s) failed to sync. First error: ${failures[0]}")
    wf.setTitleMessage('Ohalo sync failed')
    wf.setUserMessage("All ${failed} classification(s) failed to sync. First error: ${failures[0]}")
    throw wf
}

// --- Helpers ---------------------------------------------------------------

def addAttr(List errors, UUID assetId, UUID typeId, value, String label) {
    if (value == null || (value instanceof String && value.isEmpty())) {
        return
    }
    try {
        def req = AddAttributeRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .value(value)
            .build()
        attributeApi.addAttribute(req)
    } catch (Exception attrEx) {
        errors << "${label}: ${attrEx.message}"
        loggerApi.warn("addAttribute(${label}) on ${assetId} failed: ${attrEx.message}")
    }
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
