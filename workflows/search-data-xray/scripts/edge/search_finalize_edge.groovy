// search_finalize_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs once the search loop has ended. On success it does what the on-prem
// script does after its fetch: creates the search-query asset, links the
// criteria, stores the query attributes and the HTML preview, computes import
// feasibility and publishes the variables the results form renders. On failure
// it only publishes what the "Search Failed" form needs.
//
// No results ZIP in this variant: the External API task caps response sizes,
// so the full result set is never streamed. The preview shows the first page;
// importing walks every page.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonSlurper

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

// --- Preview table ----------------------------------------------------------------

def shown = tuplesAsPreviewRows(rows)
def moreNote = total > shown.size()
    ? " Showing the first ${shown.size()}; import the results to bring every matching file into Collibra."
    : ''
addAttributeQuietly(queryId, ids.filesAttrTypeId, renderPreviewHtml(shown, totalDisplay, moreNote))

// --- Import feasibility (instance-wide cap + Data X-Ray paging window) -----------

int filesDomainCount = 0
try {
    filesDomainCount = countAssetsInDomain(ids.filesDomainId)
} catch (Exception countEx) {
    loggerApi.warn("Could not count assets in the Data X-Ray Files domain: ${countEx.message}")
}
def feasibility = computeImportFeasibility(total, filesDomainCount, totalDisplay)
if (feasibility.importAllowed && total > maxResultWindow) {
    // The Data X-Ray search API pages with from/size and cannot reach past its
    // max_result_window; a bigger result set cannot be walked page by page.
    feasibility.importAllowed = false
    feasibility.importWarn = false
    feasibility.importBlocked = true
    feasibility.importBlockedMessage = "Importing is disabled for this search: it matched ${totalDisplay} file(s), more than the ${maxResultWindow} results Data X-Ray can page through in one query. Narrow the query (add a label or annotator, or split it per datasource) and search again.".toString()
}

// --- Publish process variables for the results form -------------------------

execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', displayQuery(queryString))
execution.setVariable('resultCount', total)
execution.setVariable('resultCountDisplay', totalDisplay)
execution.setVariable('shownCount', shown.size())
execution.setVariable('attachmentName', '')
execution.setVariable('attachmentDescription', 'Results are not attached as a file in the Collibra Cloud + Edge edition — import them to work with the full set in Collibra.')
execution.setVariable('zipCapped', false)
execution.setVariable('queryAssetId', queryId.toString())
execution.setVariable('importAllowed', feasibility.importAllowed)
execution.setVariable('importWarn', feasibility.importWarn)
execution.setVariable('importBlocked', feasibility.importBlocked)
execution.setVariable('importBlockedMessage', feasibility.importBlockedMessage)
execution.setVariable('filesDomainCount', filesDomainCount)
execution.setVariable('importDecision', false)
execution.setVariable('keepInSync', false)

loggerApi.info("Search Data X-Ray complete: asset=${queryId}, total=${totalDisplay}, shown=${shown.size()}, importAllowed=${feasibility.importAllowed} (files domain holds ${filesDomainCount})")
