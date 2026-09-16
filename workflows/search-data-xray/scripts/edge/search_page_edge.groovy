// search_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task that runs after every External API task of the search phase:
//   catalogue stages → compose the query_items → first results page (preview).
// Never throws: a failure is recorded in searchFailed/dxrErrorMessage and the
// loop ends, so the user gets a "Search Failed" task instead of a stuck job.
//
// Reads:  dxrStage, dxrCatalogue*, searchCriteria, dxrPreviewLimit, dataxrayUrl
// Writes: dxrQueryItems, searchPreviewRows (JSON tuples), resultCount,
//         dxrMaxResultWindow, searchFailed, dxrErrorMessage, hasMoreWork

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}

def fail = { String msg ->
    loggerApi.error("Search Data X-Ray failed: ${msg}")
    execution.setVariable('searchFailed', true)
    execution.setVariable('dxrErrorMessage', msg)
    execution.setVariable('hasMoreWork', false)
}

def stage = (execution.getVariable('dxrStage') ?: '').toString()
def r = readEdgeResponse()
if (!r.ok) {
    if (edgeRetry("Data X-Ray ${stage} request", r.error)) { return }
    fail("${r.error}. Check that the Edge HTTP connection '${execution.getVariable('dataxrayConnectionName')}' exists, points at Data X-Ray and has valid credentials.".toString())
    return
}
edgeResetRetries()

if (stage == 'catalogue') {
    try {
        if (!advanceEdgeCatalogue(r.body)) { return }   // next catalogue request is armed
    } catch (Exception catEx) {
        fail("Could not read the Data X-Ray catalogue: ${catEx.message}".toString())
        return
    }
    // Catalogue complete → translate the criteria into a Data X-Ray query.
    def criteria = new JsonSlurper().parseText(execution.getVariable('searchCriteria').toString())
    def q = composeEdgeQueryItems([
        labelDxrIds: criteria.labels*.dxrId,               labelNames: criteria.labels*.name,
        extractorDxrIds: criteria.extractors*.dxrId,       extractorNames: criteria.extractors*.name,
        annotatorDxrIds: criteria.annotators*.dxrId,       annotatorNames: criteria.annotators*.name,
        filter: criteria.filter,
        filterAnnotatorDxrIds: criteria.filterAnnotators*.dxrId, filterAnnotatorNames: criteria.filterAnnotators*.name,
    ], loadEdgeCatalogue())
    if (!q.unresolved.isEmpty()) {
        fail("Cannot search: ${q.unresolved.join(', ')} ${q.unresolved.size() == 1 ? 'is' : 'are'} not visible to Data X-Ray through this connection (the Collibra asset's Data X-Ray ID is missing or stale, or the connection's Data X-Ray user cannot see that classification). Run Sync Data X-Ray Classifications with the same connection and try again.".toString())
        return
    }
    execution.setVariable('dxrQueryItems', JsonOutput.toJson(q.items))
    int previewLimit = (execution.getVariable('dxrPreviewLimit') ?: 50) as int
    startEdgeFilesPage(q.items, 0, previewLimit, null)
    return
}

// stage == 'files' — the preview page.
def page
try {
    page = parseEdgeSearchPage(r.body)
} catch (Exception parseEx) {
    if (edgeRetry('Data X-Ray search request', parseEx.message)) { return }
    fail("Data X-Ray search response could not be parsed: ${parseEx.message}".toString())
    return
}
def cat = loadEdgeCatalogue()
def dataxrayUrl = (execution.getVariable('dataxrayUrl') ?: '').toString()
def rows = page.hits.collect { tupleFromSearchHit(it, cat, dataxrayUrl) }.findAll { it != null }
execution.setVariable('searchPreviewRows', JsonOutput.toJson(rows))
execution.setVariable('resultCount', page.total)
execution.setVariable('dxrMaxResultWindow', page.maxResultWindow)
execution.setVariable('hasMoreWork', false)
loggerApi.info("Data X-Ray returned ${page.total} file(s); preview holds ${rows.size()} (max_result_window ${page.maxResultWindow})")
