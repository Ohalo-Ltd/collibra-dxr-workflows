// search_data_xray.groovy  (on-prem variant: direct HTTPS to Data X-Ray)
//
// Builds a Data X-Ray search query from a set of Collibra classification
// assets, then previews the matching files.
//
// Flow:
//   1. Read & validate workflow configuration variables (hidden admin settings).
//   2. Read the user's picks from the start form: a name, any combination of
//      annotators / extractors / labels (Collibra asset pickers, multi-value),
//      an optional free-text phrase filter (+ the annotators it is searched in),
//      and a description.
//   3. Create a search-query asset in the configured domain.
//   4. Link that asset to each chosen classification via the configured
//      relation type, resolving each classification's name along the way.
//   5. Compose the equivalent Data X-Ray query string from those names plus the
//      phrase filter (a "contains" match scoped to the picked annotators), and
//      store it on the asset as its Description.
//   6. Call the Data X-Ray files API with that query, streaming every matching
//      file into a CSV that is compressed on the fly into a ZIP, and keep the
//      first `maxResults` rows for an inline HTML preview.
//   7. Attach the results ZIP to the asset, store the HTML preview table, and
//      publish process variables the results form renders.
//
// The only configuration variables are the per-instance secrets (base URL and
// auth token), set by an admin on the workflow settings page; they ship with a
// "<paste … here>" sentinel default that is treated as "unset". All
// operating-model IDs are fixed constants (shared/dxr_model.groovy), created by
// the configure-data-xray-workflows admin workflow.
//
// Process variables produced (for the results form / audit):
//   conditionName  (String)  – the search name the user entered
//   queryString    (String)  – the composed Data X-Ray query, or "(all files)"
//   searchUrl      (String)  – the full Data X-Ray files API URL that was called
//   resultCount        (Integer) – total matching files (exact)
//   resultCountDisplay (String)  – resultCount as text
//   shownCount         (Integer) – rows shown in the inline preview table (≤ maxResults)
//   attachmentName     (String)  – filename of the attached results ZIP, or "" if none
//   attachmentDescription (String) – sentence the results form shows about the attachment
//   zipCapped          (Boolean) – true if the ZIP was truncated at the 25 MB size cap
//   queryAssetId       (String)  – UUID of the created search-query asset
//   importAllowed      (Boolean) – results may be imported as file assets (> 0 results
//                                  and the projected Data X-Ray Files domain population
//                                  stays within the instance-wide 25k cap)
//   importWarn         (Boolean) – import allowed but large (> 10k files): the results
//                                  form shows a duration warning
//   importBlocked      (Boolean) – results exist but importing would exceed the cap
//   importBlockedMessage (String) – human-readable refusal, '' unless importBlocked
//   filesDomainCount   (Integer) – current Data X-Ray Files domain population
//   importDecision / keepInSync (Boolean) – defaults (false) for the results form's
//                                  decision fields; the form overwrites them
//
// Every matching file is streamed straight into a CSV that is compressed on the
// fly into a ZIP (only compressed bytes are ever held in memory), which is then
// attached to the search-query asset. The response is never buffered whole, so
// the exact total is counted without loading every file into memory. The ZIP is
// capped at 25 MB: once that size is reached the CSV stops growing but the total
// keeps counting, so the attachment is a bounded sample of a very large result set.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:search_common.groovy}}

def ids = dxrModelIds()

// --- Read & validate workflow configuration variables -----------------------

def cfg = readRequiredConfig([
    dataxrayUrl      : 'Data X-Ray Base URL',
    dataxrayAuthToken: 'Data X-Ray Auth Token (Bearer)',
])
if (!cfg.missing.isEmpty()) {
    def detailMsg = "Cannot run Search Data X-Ray — the following workflow configuration variable(s) are not set:${missingConfigBullets(cfg.missing)}\n\nOpen the workflow's settings page and provide a value for each, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('Search Data X-Ray misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
}
def dataxrayUrl       = normalizeBaseUrl(cfg.config.dataxrayUrl)
def dataxrayAuthToken = cfg.config.dataxrayAuthToken

// Max files to preview on the query asset.
def maxResults = 50

// --- Read user inputs from the start form -----------------------------------

def conditionName = (execution.getVariable('conditionName') ?: '').toString().trim()
if (conditionName.isEmpty()) {
    def msg = 'A name for the search query is required.'
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Search Data X-Ray')
    wf.setUserMessage(msg)
    throw wf
}

def annotatorIds = toIdList(execution.getVariable('annotator'))
def extractorIds = toIdList(execution.getVariable('extractor'))
def labelIds     = toIdList(execution.getVariable('label'))
def filter       = (execution.getVariable('filter') ?: '').toString().trim()
def filterAnnotatorIds = toIdList(execution.getVariable('filterAnnotator'))
def description  = (execution.getVariable('description') ?: '').toString().trim()

// --- Create the search-query asset ------------------------------------------

def queryId = createQueryAsset(conditionName, ids)

// --- Resolve classification names and link them to the query asset ----------

def labelNames     = resolveAndRelate(labelIds,     queryId, ids.groupsRelationTypeId)
def annotatorNames = resolveAndRelate(annotatorIds, queryId, ids.groupsRelationTypeId)
def extractorNames = resolveAndRelate(extractorIds, queryId, ids.groupsRelationTypeId)
// Annotators the annotated-text filter is scoped to. Only meaningful with a
// phrase; without one they would silently do nothing, so they are dropped.
def filterAnnotatorNames = filter.isEmpty() ? [] : resolveAndRelate(filterAnnotatorIds, queryId, ids.textFilterRelTypeId)
if (filter.isEmpty() && !filterAnnotatorIds.isEmpty()) {
    loggerApi.warn("Ignoring ${filterAnnotatorIds.size()} annotated-text annotator(s): no annotated text was entered")
}

// --- Compose the Data X-Ray query string ------------------------------------

def queryString = composeDxrQuery(labelNames, extractorNames, annotatorNames, filter, filterAnnotatorNames)
if (queryString.isEmpty()) {
    loggerApi.warn('No annotators, extractors, labels or filter selected — querying all files')
}
loggerApi.info("Composed Data X-Ray query: ${displayQuery(queryString)}")

writeQueryAttributes(queryId, ids, description, queryString, filter)

// --- Call the Data X-Ray files API ------------------------------------------

def searchUrl = dxrFilesUrl(dataxrayUrl, queryString)

def MAX_ZIP_BYTES  = 25 * 1024 * 1024  // 25 MB cap on the attached results ZIP
def FETCH_ATTEMPTS = 3
def result
try {
    result = withDxrRetries(FETCH_ATTEMPTS, 5000, 'Data X-Ray fetch') {
        fetchAndArchive(searchUrl, dataxrayAuthToken, maxResults, MAX_ZIP_BYTES)
    }
} catch (Exception fetchEx) {
    loggerApi.error("Failed to fetch files from Data X-Ray after ${FETCH_ATTEMPTS} attempts: ${fetchEx.message}")
    def wf = new WorkflowException("Data X-Ray file search failed: ${fetchEx.message}", fetchEx)
    wf.setTitleMessage('Search Data X-Ray failed')
    // NOTE: this exception rolls the whole task back — the query asset
    // created above does NOT survive, so tell the user the truth.
    wf.setUserMessage("Fetching matching files from Data X-Ray failed after ${FETCH_ATTEMPTS} attempts (${fetchEx.message}). Nothing was saved — this is usually a transient Data X-Ray stall; start the search again.")
    throw wf
}

def shown = result.shown
int total = result.total
boolean zipCapped = result.zipCapped
def zipBytes = result.zipBytes
def totalDisplay = total.toString()

loggerApi.info("Data X-Ray returned ${total} file(s); ZIP is ${zipBytes.length} byte(s)${zipCapped ? ' (capped at 25 MB)' : ''}, preview shows ${shown.size()}")

// --- Attach the full results as a ZIP'd CSV to the search-query asset ---------

def attachmentName = safeAttachmentName(conditionName, '-results.zip')
if (total == 0 || !attachFileToAsset(queryId, attachmentName, zipBytes)) {
    // Best-effort: the asset and inline preview still stand if the attach fails.
    attachmentName = ''
}
def attachmentDescription = attachmentName
    ? "The full result set is attached to the asset as a ZIP'd CSV (${attachmentName})${zipCapped ? ', capped at 25 MB' : ''}. Open the asset to review the preview and download the attachment."
    : 'Open the asset to review the preview.'

// --- Store the first maxResults matches as an HTML preview table --------------

def moreNote = total > shown.size()
    ? " The full result set (${totalDisplay} file(s)${zipCapped ? ', capped at 25 MB' : ''}) is attached to this asset as ${attachmentName ?: 'a ZIP'}."
    : ''
addAttributeQuietly(queryId, ids.filesAttrTypeId, renderPreviewHtml(shown, totalDisplay, moreNote))

// --- Import feasibility (instance-wide cap) ----------------------------------

int filesDomainCount = 0
try {
    filesDomainCount = countAssetsInDomain(ids.filesDomainId)
} catch (Exception countEx) {
    loggerApi.warn("Could not count assets in the Data X-Ray Files domain: ${countEx.message}")
}
def feasibility = computeImportFeasibility(total, filesDomainCount, totalDisplay)

// --- Publish process variables for the results form -------------------------

execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', displayQuery(queryString))
execution.setVariable('searchUrl', searchUrl)
execution.setVariable('resultCount', total)
execution.setVariable('resultCountDisplay', totalDisplay)
execution.setVariable('shownCount', shown.size())
execution.setVariable('attachmentName', attachmentName)
execution.setVariable('attachmentDescription', attachmentDescription)
execution.setVariable('zipCapped', zipCapped)
execution.setVariable('queryAssetId', queryId.toString())
execution.setVariable('importAllowed', feasibility.importAllowed)
execution.setVariable('importWarn', feasibility.importWarn)
execution.setVariable('importBlocked', feasibility.importBlocked)
execution.setVariable('importBlockedMessage', feasibility.importBlockedMessage)
execution.setVariable('filesDomainCount', filesDomainCount)
// Defaults for the results form's decision fields; completing the form
// overwrites them.
execution.setVariable('importDecision', false)
execution.setVariable('keepInSync', false)

loggerApi.info("Search Data X-Ray complete: asset=${queryId}, total=${totalDisplay}, shown=${shown.size()}, attachment=${attachmentName ?: '(none)'}, importAllowed=${feasibility.importAllowed} (files domain holds ${filesDomainCount})")

// --- Helpers ----------------------------------------------------------------

// Stream the Data X-Ray files endpoint: every row is written to a CSV that is
// compressed on the fly into a single-entry ZIP (results.csv) held in a
// ByteArrayOutputStream — so only *compressed* bytes accumulate in memory. The
// first `keepLimit` rows are also retained for the inline preview table.
//
// The ZIP stops growing once its compressed size reaches `maxZipBytes` (we stop
// a headroom margin early so the final flush + central directory stay under the
// cap), but every row is still counted, so `total` is always exact.
//
// Returns [shown: List (≤ keepLimit), total: int, zipCapped: boolean, zipBytes: byte[]].
def fetchAndArchive(String url, String authToken, int keepLimit, int maxZipBytes) {
    // Stop the CSV a little before the hard cap so the deflater's final flush and
    // the ZIP central directory (written on close) cannot push it over 25 MB.
    def stopAt = Math.max(0, maxZipBytes - 256 * 1024)

    def shown = []
    int total = 0
    boolean zipCapped = false

    def baos = new ByteArrayOutputStream()
    def zos = new ZipOutputStream(baos)
    zos.putNextEntry(new ZipEntry('results.csv'))
    def csv = new OutputStreamWriter(zos, StandardCharsets.UTF_8)
    csv.write('Datasource,Path\r\n')

    // A 2-minute silence between rows means the server has stalled — fail fast
    // so the retry loop gets its chance instead of hanging the task.
    streamDxrNdjson(url, authToken, 120_000) { f ->
        if (total < keepLimit) {
            shown << f
        }
        if (!zipCapped) {
            csv.write(csvEscape(dataSourceName(f)) + ',' + csvEscape(filePath(f)) + '\r\n')
            csv.flush()
            if (baos.size() >= stopAt) {
                zipCapped = true
            }
        }
        total++
    }

    try {
        csv.flush()
        zos.closeEntry()
        zos.close()
    } catch (Exception ignored) {
        // best-effort: baos already holds the compressed bytes written so far
    }

    return [shown: shown, total: total, zipCapped: zipCapped, zipBytes: baos.toByteArray()]
}
