// rerun_collector_edge.groovy  (Collibra Cloud + Edge variant)
//
// First, SYNCHRONOUS task of "Rerun DataXray Search". Same guards and
// criteria rebuild as the on-prem script (shared/rerun_common.groovy); instead
// of fetching, it translates the criteria into a DataXray query (numeric
// index ids stamped on the classification assets by the Edge-edition sync),
// snapshots the previously returned files, builds the classification index
// from Collibra and arms page 0 of the results (rerun_page_edge.groovy).
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

announceDxrVersion('Rerun DataXray Search')
def ids = dxrModelIds()

// --- Headless normalization ----------------------------------------------------

boolean headless = (execution.getVariable('headless') ?: 'false').toString().trim() == 'true'
execution.setVariable('headless', headless ? 'true' : 'false')

def abort = { String title, String message ->
    loggerApi.error("Rerun DataXray Search aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    zeroEdgeImportCounters(0, dxrEdgeDefaultPageSize())
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('dxrFetchComplete', false)
    execution.setVariable('previousFileIds', '[]')
    execution.setVariable('rerunRetiredCount', 0)
    execution.setVariable('rerunUnlinkedCount', 0)
    execution.setVariable('rerunNoAutoImport', false)
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
    abort('Rerun DataXray Search misconfigured',
        "The 'Edge HTTP connection name (DataXray)' configuration variable is not set on this workflow. Open its settings page and provide it.")
    return
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)
int pageSize = edgePageSize(execution.getVariable('dataxrayPageSize'))
execution.setVariable('dataxrayPageSize', pageSize.toString())

// The 'Not in any search' status and the "formerly" relation types come from
// Configure DataXray Workflows (v2.4+): refuse to run without them.
def modelGap = rerunModelGap(ids)
if (modelGap) {
    abort('Rerun DataXray Search — run Configure first', modelGap)
    return
}

// --- The query asset ---------------------------------------------------------------

def queryId = item?.getId()
if (queryId == null) {
    abort('Rerun DataXray Search', 'No query asset — this workflow must be started from an Unstructured Data Query asset.')
    return
}
def queryAsset = assetApi.getAsset(queryId)
if (queryAsset.getType()?.getId() != ids.queryAssetTypeId) {
    abort('Rerun DataXray Search',
        "'${queryAsset.getName()}' is a ${queryAsset.getType()?.getName()} asset — this workflow can only rerun an Unstructured Data Query asset (a saved search created by Search DataXray).")
    return
}
def conditionName = queryAsset.getName()
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryAssetId', queryId.toString())

// Tagged dataxray-no-auto-import: refresh the file assets already in Collibra
// and create none for new matching files.
boolean noAutoImport = assetTagNames(queryId).contains(dxrNoAutoImportTag())
execution.setVariable('rerunNoAutoImport', noAutoImport)

// --- Rebuild the criteria from relations + the stored filter -------------------------

def criteria = rebuildQueryCriteria(queryId, ids)
if (criteria.ignoredCriteria > 0) {
    loggerApi.warn("Rerun of '${conditionName}': ignoring ${criteria.ignoredCriteria} related asset(s) that are not Label/Extractor/Annotator classifications")
}
if (!criteria.deadCriteria.isEmpty()) {
    abort('Rerun DataXray Search — criterion no longer exists',
        "Cannot rerun '${conditionName}': ${criteria.deadCriteria.join('; ')}. Rerunning without it would broaden the search and import files you never asked for. Recreate the classification in DataXray (and run Sync DataXray Classifications), or create a new search.")
    return
}
if (!criteria.hasCriteria) {
    abort('Rerun DataXray Search — no saved criteria',
        "Cannot rerun '${conditionName}': it has no linked classifications and no annotated-text filter, so its criteria cannot be reconstructed. Create a new search instead.")
    return
}

def queryString = composeDxrQuery(criteria.labelNames, criteria.extractorNames, criteria.annotatorNames,
                                  criteria.filter, criteria.filterAnnotatorNames)
loggerApi.info("Rerun of '${conditionName}' — rebuilt DataXray query: ${displayQuery(queryString)}")
recordQueryAttribute(queryId, ids, displayQuery(queryString))

// --- Classification index from Collibra; translate the criteria ----------------------

def classIndex = buildEdgeClassificationIndex(ids)
execution.setVariable('classIndex', JsonOutput.toJson(classIndex))

def q = composeEdgeQueryItems(criteria, classIndex.annotatorIndexIds)
if (!q.unresolved.isEmpty()) {
    abort('Rerun DataXray Search', edgeUnresolvedMessage("rerun '${conditionName}'".toString(), q.unresolved))
    return
}
execution.setVariable('dxrQueryItems', JsonOutput.toJson(q.items))

// --- Snapshot the files this query currently returns ----------------------------------

def previousFileIds = collectPreviousFileIds(queryId, ids)
execution.setVariable('previousFileIds', JsonOutput.toJson(previousFileIds as List))
execution.setVariable('filesDomainCount', countFileAssets(ids))

// --- Counters + kick off the results loop ----------------------------------------------

execution.setVariable('rerunAborted', false)
execution.setVariable('rerunAbortReason', '')
zeroEdgeImportCounters(0, pageSize)
execution.setVariable('rerunRetiredCount', 0)
execution.setVariable('rerunUnlinkedCount', 0)
execution.setVariable('dxrFetchComplete', false)
execution.setVariable('rerunPageCount', 0)
execution.setVariable('rerunRetireUnsafe', false)
execution.setVariable('dxrDatasourceNames', '{}')
beginEdgeFilesPass()
startEdgeFilesPage(q.items, 0, pageSize, null)
loggerApi.info("Rerun of '${conditionName}' via Edge connection '${connectionName}': ${previousFileIds.size()} previously returned file(s); page 0 armed")
