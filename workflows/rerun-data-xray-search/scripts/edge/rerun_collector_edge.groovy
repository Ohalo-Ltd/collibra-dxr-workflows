// rerun_collector_edge.groovy  (Collibra Cloud + Edge variant)
//
// First, SYNCHRONOUS task of "Rerun Data X-Ray Search". Same guards and
// criteria rebuild as the on-prem script (shared/rerun_common.groovy); instead
// of fetching, it snapshots the previously returned files, builds the
// classification index and arms the first request of the loop (the Data X-Ray
// catalogues, then the results pages — rerun_page_edge.groovy).
//
// Headless mode: the nightly driver starts this workflow with 'headless'='true'.
// Headless runs never throw; every abort path sets rerunAborted=true so the
// page/retire tasks no-op and the summary user task is skipped.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:rerun_common.groovy}}
// {{include:dxr_edge.groovy}}

def ids = dxrModelIds()

// --- Headless normalization ----------------------------------------------------

boolean headless = (execution.getVariable('headless') ?: 'false').toString().trim() == 'true'
execution.setVariable('headless', headless ? 'true' : 'false')

def abort = { String title, String message ->
    loggerApi.error("Rerun Data X-Ray Search aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    zeroEdgeImportCounters(0, 50)
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('dxrFetchComplete', false)
    execution.setVariable('previousFileIds', '[]')
    execution.setVariable('rerunRetiredCount', 0)
    execution.setVariable('rerunUnlinkedCount', 0)
    if (!headless) {
        def wf = new WorkflowException(message)
        wf.setTitleMessage(title)
        wf.setUserMessage(message)
        throw wf
    }
}

// --- Config -----------------------------------------------------------------------

def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    abort('Rerun Data X-Ray Search misconfigured',
        "The 'Edge HTTP connection name (Data X-Ray)' configuration variable is not set on this workflow. Open its settings page and provide it.")
    return
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)
int pageSize = edgePageSize(execution.getVariable('dataxrayPageSize'), 50)
execution.setVariable('dataxrayPageSize', pageSize.toString())

// --- The query asset ---------------------------------------------------------------

def queryId = item?.getId()
if (queryId == null) {
    abort('Rerun Data X-Ray Search', 'No query asset — this workflow must be started from an Unstructured Data Query asset.')
    return
}
def queryAsset = assetApi.getAsset(queryId)
if (queryAsset.getType()?.getId() != ids.queryAssetTypeId) {
    abort('Rerun Data X-Ray Search',
        "'${queryAsset.getName()}' is a ${queryAsset.getType()?.getName()} asset — this workflow can only rerun an Unstructured Data Query asset (a saved search created by Search Data X-Ray).")
    return
}
def conditionName = queryAsset.getName()
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryAssetId', queryId.toString())

// --- Rebuild the criteria from relations + the stored filter -------------------------

def criteria = rebuildQueryCriteria(queryId, ids)
if (criteria.ignoredCriteria > 0) {
    loggerApi.warn("Rerun of '${conditionName}': ignoring ${criteria.ignoredCriteria} related asset(s) that are not Label/Extractor/Annotator classifications")
}
if (!criteria.deadCriteria.isEmpty()) {
    abort('Rerun Data X-Ray Search — criterion no longer exists',
        "Cannot rerun '${conditionName}': ${criteria.deadCriteria.join('; ')}. Rerunning without it would broaden the search and import files you never asked for. Recreate the classification in Data X-Ray (and run Sync Data X-Ray Classifications), or create a new search.")
    return
}
if (!criteria.hasCriteria) {
    abort('Rerun Data X-Ray Search — no saved criteria',
        "Cannot rerun '${conditionName}': it has no linked classifications and no annotated-text filter, so its criteria cannot be reconstructed. Create a new search instead.")
    return
}

def queryString = composeDxrQuery(criteria.labelNames, criteria.extractorNames, criteria.annotatorNames,
                                  criteria.filter, criteria.filterAnnotatorNames)
loggerApi.info("Rerun of '${conditionName}' — rebuilt Data X-Ray query: ${displayQuery(queryString)}")
recordQueryAttribute(queryId, ids, displayQuery(queryString))
execution.setVariable('rerunCriteria', JsonOutput.toJson([
    labelDxrIds: criteria.labelDxrIds, labelNames: criteria.labelNames,
    extractorDxrIds: criteria.extractorDxrIds, extractorNames: criteria.extractorNames,
    annotatorDxrIds: criteria.annotatorDxrIds, annotatorNames: criteria.annotatorNames,
    filter: criteria.filter,
    filterAnnotatorDxrIds: criteria.filterAnnotatorDxrIds, filterAnnotatorNames: criteria.filterAnnotatorNames,
]))

// --- Snapshot the files this query currently returns; index the classifications ----

def previousFileIds = collectPreviousFileIds(queryId, ids)
execution.setVariable('previousFileIds', JsonOutput.toJson(previousFileIds as List))
execution.setVariable('filesDomainCount', countAssetsInDomain(ids.filesDomainId))

def classIndex = buildClassificationIndex(ids.classificationsDomainId, ids.dataxrayIdAttrTypeId)
execution.setVariable('classIndex', JsonOutput.toJson([
    byDxrId: classIndex.byDxrId.collectEntries { k, v -> [(k.toString()): v.toString()] },
    byName : classIndex.byName.collectEntries  { k, v -> [(k.toString()): v.toString()] },
]))

// --- Counters + kick off the request loop -----------------------------------------------

execution.setVariable('rerunAborted', false)
execution.setVariable('rerunAbortReason', '')
zeroEdgeImportCounters(0, pageSize)
execution.setVariable('rerunRetiredCount', 0)
execution.setVariable('rerunUnlinkedCount', 0)
execution.setVariable('dxrFetchMode', 'import')
execution.setVariable('dxrFetchComplete', false)
execution.setVariable('rerunPageCount', 0)
startEdgeCatalogue()
loggerApi.info("Rerun of '${conditionName}' via Edge connection '${connectionName}': ${previousFileIds.size()} previously returned file(s); catalogue requests armed")
