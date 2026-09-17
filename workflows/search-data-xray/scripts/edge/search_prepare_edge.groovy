// search_prepare_edge.groovy  (Collibra Cloud + Edge variant)
//
// First, SYNCHRONOUS task of Search Data X-Ray: everything that can fail with a
// message the user sees in the start dialog happens here — configuration,
// inputs, resolving the picked classifications to their Data X-Ray ids and
// composing the query. Nothing is written to Collibra yet (the External API
// tasks that follow are asynchronous, so a failure after them cannot roll back
// into the user's dialog; the query asset is created in
// search_finalize_edge.groovy once the results are in).
//
// The query needs each criterion's numeric Data X-Ray index id, which the
// Edge-edition classification sync stamps on the Collibra asset ("Data X-Ray
// Index ID"). A criterion without one fails the search here, with a message
// telling the user to run that sync first.
//
// Process variables produced: searchCriteria (JSON), dxrQueryItems (JSON),
// queryString, conditionName, dataxrayUrl ('' when unset), searchFailed=false,
// dxrErrorMessage='', dxrDatasourceNames='{}', plus the dxr* request/loop variables.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:search_common.groovy}}
// {{include:dxr_edge.groovy}}

def ids = dxrModelIds()

def failNow = { String title, String msg ->
    loggerApi.error(msg)
    def wf = new WorkflowException(msg)
    wf.setTitleMessage(title)
    wf.setUserMessage(msg)
    throw wf
}

// --- Configuration ------------------------------------------------------------

def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    failNow('Search Data X-Ray misconfigured',
        "Cannot run Search Data X-Ray — the 'Edge HTTP connection name (Data X-Ray)' configuration variable is not set.\n\nOpen the workflow's settings page and enter the name of the HTTP connection to Data X-Ray on your Edge site, then start the workflow again.")
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)
execution.setVariable('dataxrayPageSize', edgePageSize(execution.getVariable('dataxrayPageSize')).toString())

// --- Inputs -------------------------------------------------------------------

def conditionName = (execution.getVariable('conditionName') ?: '').toString().trim()
if (conditionName.isEmpty()) {
    failNow('Search Data X-Ray', 'A name for the search query is required.')
}
def filter      = (execution.getVariable('filter') ?: '').toString().trim()
def description = (execution.getVariable('description') ?: '').toString().trim()

def labels     = resolvePickedClassifications(toIdList(execution.getVariable('label')),     ids.dataxrayIdAttrTypeId, ids.dataxrayIndexIdAttrTypeId)
def extractors = resolvePickedClassifications(toIdList(execution.getVariable('extractor')), ids.dataxrayIdAttrTypeId, ids.dataxrayIndexIdAttrTypeId)
def annotators = resolvePickedClassifications(toIdList(execution.getVariable('annotator')), ids.dataxrayIdAttrTypeId, ids.dataxrayIndexIdAttrTypeId)
def filterAnnotatorIds = toIdList(execution.getVariable('filterAnnotator'))
def filterAnnotators = filter.isEmpty() ? [] : resolvePickedClassifications(filterAnnotatorIds, ids.dataxrayIdAttrTypeId, ids.dataxrayIndexIdAttrTypeId)
if (filter.isEmpty() && !filterAnnotatorIds.isEmpty()) {
    loggerApi.warn("Ignoring ${filterAnnotatorIds.size()} annotated-text annotator(s): no annotated text was entered")
}

def queryString = composeDxrQuery(labels*.name, extractors*.name, annotators*.name, filter, filterAnnotators*.name)
if (queryString.isEmpty()) {
    loggerApi.warn('No annotators, extractors, labels or filter selected — querying all files')
}
loggerApi.info("Composed Data X-Ray query: ${displayQuery(queryString)}")

// --- Translate the criteria into a Data X-Ray query ------------------------------

// A phrase with no picked annotators is searched in every annotator Collibra knows.
def allAnnotatorIndexIds = (!filter.isEmpty() && filterAnnotators.isEmpty())
    ? buildEdgeClassificationIndex(ids).annotatorIndexIds
    : []
def q = composeEdgeQueryItems([
    labelIndexIds: labels*.indexId,         labelNames: labels*.name,
    extractorIndexIds: extractors*.indexId, extractorNames: extractors*.name,
    annotatorIndexIds: annotators*.indexId, annotatorNames: annotators*.name,
    filter: filter,
    filterAnnotatorIndexIds: filterAnnotators*.indexId, filterAnnotatorNames: filterAnnotators*.name,
], allAnnotatorIndexIds)
if (!q.unresolved.isEmpty()) {
    failNow('Search Data X-Ray', edgeUnresolvedMessage('search', q.unresolved))
}

def asJson = { List picked -> picked.collect { [id: it.id.toString(), name: it.name, dxrId: it.dxrId, indexId: it.indexId] } }
execution.setVariable('searchCriteria', JsonOutput.toJson([
    conditionName: conditionName, description: description, filter: filter, queryString: queryString,
    labels: asJson(labels), extractors: asJson(extractors), annotators: asJson(annotators),
    filterAnnotators: asJson(filterAnnotators),
]))
execution.setVariable('dxrQueryItems', JsonOutput.toJson(q.items))
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', displayQuery(queryString))
execution.setVariable('searchUrl', "edge:${connectionName} POST /api/indexed-files/search".toString())
execution.setVariable('searchFailed', false)
execution.setVariable('dxrErrorMessage', '')
execution.setVariable('dxrDatasourceNames', '{}')
execution.setVariable('dxrFetchComplete', false)

// --- Kick off the request loop with the preview page -----------------------------

startEdgeFilesPage(q.items, 0, 50, null)
loggerApi.info("Search Data X-Ray via Edge connection '${connectionName}': preview page armed (${q.items.size()} query item(s))")
