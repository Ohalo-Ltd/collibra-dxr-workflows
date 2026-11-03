// rerun_collector_edge.groovy  (Collibra Cloud + Edge variant)
//
// First, SYNCHRONOUS task of "Rerun DataXray Search". Same guards and
// criteria rebuild as the on-prem script (shared/rerun_common.groovy); instead
// of fetching, it translates the criteria into a DataXray query (numeric
// index ids stamped on the classification assets by the Edge-edition sync),
// snapshots the previously returned files and builds the classification index
// from Collibra. An interactive run then arms the count probe
// (rerun_count_edge.groovy: how many files match now, for the Rerun Options
// task, v2.10); a headless run arms nothing — rerun_apply_options_edge.groovy
// arms page 0 of the results (rerun_page_edge.groovy) for both.
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
    execution.setVariable('rerunRetireSkipped', false)
    execution.setVariable('rerunRetireSkipReason', '')
    zeroRerunExistsSummary()
    execution.setVariable('rerunExistsMode', '')
    execution.setVariable('rerunExistsCandidates', '[]')
    execution.setVariable('rerunTagKeepInSync', false)
    execution.setVariable('rerunTagNoAutoImport', false)
    execution.setVariable('filesDomainCount', 0)
    zeroRerunOptions()
    if (!headless) {
        def wf = new WorkflowException(message)
        wf.setTitleMessage(title)
        wf.setUserMessage(message)
        throw wf
    }
}

// --- Config -----------------------------------------------------------------------

// The connection settings are Search DataXray's (v2.10.1; dxr_config.groovy).
// The External API tasks name their connection with ${dataxrayConnectionName},
// which resolves to the process variable set here.
def adopted = adoptSearchDataXraySettings(['dataxrayConnectionName', 'dataxrayUrl', 'dataxrayPageSize'])
def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    abort('Rerun DataXray Search misconfigured',
        searchSettingsMissingMessage("the 'Edge HTTP connection name (DataXray)' is", adopted))
    return
}
execution.setVariable('dataxrayConnectionName', connectionName)
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
    abort('Rerun DataXray Search', 'No query asset — this workflow must be started from a DataXray Sync Filter asset.')
    return
}
def queryAsset = assetApi.getAsset(queryId)
if (queryAsset.getType()?.getId() != ids.queryAssetTypeId) {
    abort('Rerun DataXray Search',
        "'${queryAsset.getName()}' is a ${queryAsset.getType()?.getName()} asset — this workflow can only rerun a DataXray Sync Filter asset (a saved search created by Search DataXray).")
    return
}
def conditionName = queryAsset.getName()
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryAssetId', queryId.toString())

// The sync filter's options as they stand (its tags): they prefill the Rerun
// Options task, and a headless run follows them.
def queryTags = assetTagNames(queryId)
boolean tagKeepInSync = queryTags.contains(dxrKeepInSyncTag())
boolean tagNoAutoImport = queryTags.contains(dxrNoAutoImportTag())
execution.setVariable('rerunTagKeepInSync', tagKeepInSync)
execution.setVariable('rerunTagNoAutoImport', tagNoAutoImport)
execution.setVariable('rerunCreateNew', !tagNoAutoImport)

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
// Written to the DataXray Query attribute once the options are applied (applyRerunOptions).
execution.setVariable('rerunQueryDisplay', displayQuery(queryString))

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
execution.setVariable('rerunRetireSkipped', false)
execution.setVariable('rerunRetireSkipReason', '')
zeroRerunExistsSummary()
execution.setVariable('rerunExistsMode', '')
execution.setVariable('rerunExistsCandidates', '[]')
execution.setVariable('dxrFetchComplete', false)
execution.setVariable('rerunPageCount', 0)
execution.setVariable('rerunRetireUnsafe', false)
execution.setVariable('dxrDatasourceNames', '{}')
if (headless) {
    loggerApi.info("Rerun of '${conditionName}' (headless) via Edge connection '${connectionName}': ${previousFileIds.size()} previously returned file(s)")
} else {
    armEdgeCountProbe(q.items)
    loggerApi.info("Rerun of '${conditionName}' via Edge connection '${connectionName}': ${previousFileIds.size()} previously returned file(s); count probe armed")
}
