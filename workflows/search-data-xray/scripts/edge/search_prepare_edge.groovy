// search_prepare_edge.groovy  (Collibra Cloud + Edge variant)
//
// First, SYNCHRONOUS task of Search Data X-Ray: everything that can fail with a
// message the user sees in the start dialog happens here — configuration,
// inputs, resolving the picked classifications. Nothing is written to Collibra
// yet (the External API tasks that follow are asynchronous, so a failure after
// them cannot roll back into the user's dialog; the query asset is created in
// search_finalize_edge.groovy once the results are in).
//
// Then it arms the first request of the loop (the Data X-Ray catalogues, see
// shared/dxr_edge.groovy) and hands over to the External API task.
//
// Process variables produced: searchCriteria (JSON), queryString, conditionName,
// dataxrayUrl (normalised, '' when unset), searchFailed=false, dxrErrorMessage='',
// plus the dxr* request/loop variables.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:search_common.groovy}}
// {{include:dxr_edge.groovy}}

def ids = dxrModelIds()

// --- Configuration ------------------------------------------------------------

def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    def detailMsg = "Cannot run Search Data X-Ray — the 'Edge HTTP connection name (Data X-Ray)' configuration variable is not set.\n\nOpen the workflow's settings page and enter the name of the HTTP connection to Data X-Ray on your Edge site, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('Search Data X-Ray misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)
execution.setVariable('dataxrayPageSize', edgePageSize(execution.getVariable('dataxrayPageSize'), 50).toString())

// --- Inputs -------------------------------------------------------------------

def conditionName = (execution.getVariable('conditionName') ?: '').toString().trim()
if (conditionName.isEmpty()) {
    def msg = 'A name for the search query is required.'
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Search Data X-Ray')
    wf.setUserMessage(msg)
    throw wf
}
def filter      = (execution.getVariable('filter') ?: '').toString().trim()
def description = (execution.getVariable('description') ?: '').toString().trim()

def labels     = resolvePickedClassifications(toIdList(execution.getVariable('label')),     ids.dataxrayIdAttrTypeId)
def extractors = resolvePickedClassifications(toIdList(execution.getVariable('extractor')), ids.dataxrayIdAttrTypeId)
def annotators = resolvePickedClassifications(toIdList(execution.getVariable('annotator')), ids.dataxrayIdAttrTypeId)
def filterAnnotatorIds = toIdList(execution.getVariable('filterAnnotator'))
def filterAnnotators = filter.isEmpty() ? [] : resolvePickedClassifications(filterAnnotatorIds, ids.dataxrayIdAttrTypeId)
if (filter.isEmpty() && !filterAnnotatorIds.isEmpty()) {
    loggerApi.warn("Ignoring ${filterAnnotatorIds.size()} annotated-text annotator(s): no annotated text was entered")
}

def queryString = composeDxrQuery(labels*.name, extractors*.name, annotators*.name, filter, filterAnnotators*.name)
if (queryString.isEmpty()) {
    loggerApi.warn('No annotators, extractors, labels or filter selected — querying all files')
}
loggerApi.info("Composed Data X-Ray query: ${displayQuery(queryString)}")

def asJson = { List picked -> picked.collect { [id: it.id.toString(), name: it.name, dxrId: it.dxrId] } }
execution.setVariable('searchCriteria', JsonOutput.toJson([
    conditionName: conditionName, description: description, filter: filter, queryString: queryString,
    labels: asJson(labels), extractors: asJson(extractors), annotators: asJson(annotators),
    filterAnnotators: asJson(filterAnnotators),
]))
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', displayQuery(queryString))
execution.setVariable('searchUrl', "edge:${connectionName} POST /api/indexed-files/search".toString())
execution.setVariable('searchFailed', false)
execution.setVariable('dxrErrorMessage', '')
execution.setVariable('dxrFetchMode', 'preview')
execution.setVariable('dxrPreviewLimit', 50)

// --- Kick off the request loop: catalogues first, then the first results page --

startEdgeCatalogue()
loggerApi.info("Search Data X-Ray via Edge connection '${connectionName}': catalogue requests armed")
