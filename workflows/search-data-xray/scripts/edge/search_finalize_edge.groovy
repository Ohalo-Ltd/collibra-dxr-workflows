// search_finalize_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs once the search loop has ended. On success it does what the on-prem
// script does after its fetch: creates the search-query asset, links the
// criteria, stores the query attributes and the HTML preview, computes import
// feasibility and publishes the variables the results form renders. On failure
// it only publishes what the "Search Failed" form needs.
//
// The results file: search_page_edge.groovy collected every matching file's
// datasource and path as CSV chunks (resultsFileChunk_<n>); they are zipped
// into the same results.csv the on-prem edition attaches. None is attached when
// the search matched more than dxrEdgeResultsFileMaxRows() files (or DataXray's
// paging window) or when collecting failed; the results task says which.
// Importing is not affected either way.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonSlurper
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// {{include:dxr_model.groovy}}
// {{include:dxr_query.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:search_common.groovy}}
// {{include:dxr_edge.groovy}}

def ids = dxrModelIds()
def criteria = new JsonSlurper().parseText(execution.getVariable('searchCriteria').toString())
def conditionName = criteria.conditionName.toString()

if (execution.getVariable('searchFailed') == true) {
    execution.setVariable('conditionName', conditionName)
    execution.setVariable('importAllowed', false)
    execution.setVariable('importDecision', false)
    execution.setVariable('keepInSync', false)
    execution.setVariable('autoImportNewFiles', false)
    execution.setVariable('syncOffered', false)
    return
}

def rows = new JsonSlurper().parseText((execution.getVariable('searchPreviewRows') ?: '[]').toString()) as List
int total = (execution.getVariable('resultCount') ?: 0) as int
int maxResultWindow = (execution.getVariable('dxrMaxResultWindow') ?: 10_000) as int
def totalDisplay = total.toString()
def queryString = (criteria.queryString ?: '').toString()
def filter = (criteria.filter ?: '').toString()

// --- Create the search-query asset and link the criteria ------------------------

def queryId = createQueryAsset(conditionName, ids)
def toUuids = { List picked -> (picked ?: []).collect { string2Uuid(it.id.toString()) } }
relateAssets(queryId, toUuids(criteria.labels),     ids.groupsRelationTypeId)
relateAssets(queryId, toUuids(criteria.annotators), ids.groupsRelationTypeId)
relateAssets(queryId, toUuids(criteria.extractors), ids.groupsRelationTypeId)
if (!filter.isEmpty()) {
    relateAssets(queryId, toUuids(criteria.filterAnnotators), ids.textFilterRelTypeId)
}
writeQueryAttributes(queryId, ids, (criteria.description ?: '').toString(), queryString, filter)

// --- Results file (ZIP'd CSV) ------------------------------------------------------

def fileStatus = (execution.getVariable('resultsFileStatus') ?: 'none').toString()
int fileSkipped = (execution.getVariable('resultsFileSkipped') ?: 0) as int
int fileLimit = Math.min(dxrEdgeResultsFileMaxRows(), maxResultWindow)
def attachmentName = ''
if (fileStatus == 'complete') {
    int chunks = (execution.getVariable('resultsFileChunks') ?: 0) as int
    def bytes = new ByteArrayOutputStream()
    new ZipOutputStream(bytes).withCloseable { zos ->
        zos.putNextEntry(new ZipEntry('results.csv'))
        zos.write('Datasource,Path,Full Object ID\r\n'.getBytes('UTF-8'))
        for (int n = 0; n < chunks; n++) {
            zos.write((execution.getVariable("resultsFileChunk_${n}".toString()) ?: '').toString().getBytes('UTF-8'))
        }
        zos.closeEntry()
    }
    def name = safeAttachmentName(conditionName, '-results.zip')
    if (attachFileToAsset(queryId, name, bytes.toByteArray())) { attachmentName = name }
    for (int n = 0; n < chunks; n++) {
        try { execution.removeVariable("resultsFileChunk_${n}".toString()) } catch (Exception ignored) { /* best-effort */ }
    }
}
def skippedNote = fileSkipped > 0
    ? ", except ${fileSkipped} file(s) whose result was too large to send through Collibra Edge (named in the Collibra log)"
    : ''
def attachmentDescription
if (attachmentName) {
    attachmentDescription = "The full result set is attached to the asset as a ZIP'd CSV (${attachmentName})${skippedNote}. Open the asset to review the preview and download the attachment."
} else if (fileStatus == 'tooMany') {
    attachmentDescription = "No results file is attached: this search matched ${totalDisplay} file(s), more than the ${fileLimit} a results file can hold in the Collibra Cloud + Edge edition. Refine the search to get one; importing is not affected."
} else if (fileStatus == 'failed' || fileStatus == 'complete') {
    def reason = (execution.getVariable('resultsFileError') ?: '').toString()
    attachmentDescription = "The results file could not be ${fileStatus == 'failed' ? 'built' : 'attached'}${reason ? " (${reason})" : ''}. Open the asset to review the preview, or run the search again."
} else {
    attachmentDescription = 'Open the asset to review the preview.'
}

// --- Preview table ----------------------------------------------------------------

def shown = tuplesAsPreviewRows(rows)
def moreNote = ''
if (total > shown.size()) {
    moreNote = attachmentName
        ? " The full result set (${totalDisplay} file(s)${fileSkipped > 0 ? ", less ${fileSkipped} too large to send through Edge" : ''}) is attached to this asset as ${attachmentName}."
        : fileStatus == 'tooMany'
        ? " Showing the first ${shown.size()}. The search matched more than ${fileLimit} files, too many for a results file; refine it to get one, or import the results."
        : " Showing the first ${shown.size()}; import the results to bring every matching file into Collibra."
}
addAttributeQuietly(queryId, ids.filesAttrTypeId, renderPreviewHtml(shown, totalDisplay, moreNote))

// --- Import feasibility (instance-wide cap + DataXray paging window) -----------

int filesDomainCount = 0
try {
    filesDomainCount = countFileAssets(ids)
} catch (Exception countEx) {
    loggerApi.warn("Could not count the DataXray file assets: ${countEx.message}")
}
def feasibility = computeImportFeasibility(total, filesDomainCount, totalDisplay)
if (feasibility.importAllowed && total > maxResultWindow) {
    // The DataXray search API pages with from/size and cannot reach past its
    // max_result_window; a bigger result set cannot be walked page by page.
    feasibility.importAllowed = false
    feasibility.importWarn = false
    feasibility.importBlocked = true
    feasibility.importBlockedMessage = "Importing is disabled for this search: it matched ${totalDisplay} file(s), more than the ${maxResultWindow} results DataXray can page through in one query. Narrow the query (add a label or annotator, or split it per datasource) and search again.".toString()
}

// --- Publish process variables for the results form -------------------------

execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', displayQuery(queryString))
execution.setVariable('resultCount', total)
execution.setVariable('resultCountDisplay', totalDisplay)
execution.setVariable('shownCount', shown.size())
execution.setVariable('attachmentName', attachmentName)
execution.setVariable('attachmentDescription', attachmentDescription.toString())
execution.setVariable('zipCapped', false)
execution.setVariable('queryAssetId', queryId.toString())
execution.setVariable('importAllowed', feasibility.importAllowed)
execution.setVariable('importWarn', feasibility.importWarn)
execution.setVariable('importBlocked', feasibility.importBlocked)
execution.setVariable('importBlockedMessage', feasibility.importBlockedMessage)
execution.setVariable('filesDomainCount', filesDomainCount)
execution.setVariable('importDecision', false)
execution.setVariable('keepInSync', false)
execution.setVariable('autoImportNewFiles', true)
// Not above the import limit: the first rerun would have to create them all.
execution.setVariable('syncOffered', total > 0 && total <= dxrMaxImportFiles())

loggerApi.info("Search DataXray complete: asset=${queryId}, total=${totalDisplay}, shown=${shown.size()}, resultsFile=${attachmentName ?: fileStatus}${fileSkipped ? " (${fileSkipped} skipped)" : ''}, importAllowed=${feasibility.importAllowed} (files domain holds ${filesDomainCount})")
