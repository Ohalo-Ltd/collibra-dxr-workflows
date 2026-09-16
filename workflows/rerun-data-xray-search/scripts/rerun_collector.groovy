// rerun_collector.groovy  (on-prem variant: direct HTTPS to Data X-Ray)
//
// First stage of "Rerun Data X-Ray Search" — an ASSET-scoped workflow started
// from a saved Unstructured Data Query asset (or headlessly, per query, by the
// nightly file sync driver).
//
// The saved criteria are rebuilt from the query asset (shared/rerun_common.groovy):
// its "groups" relations to classification assets, using their CURRENT names,
// plus the "Annotated Text Filter" attribute scoped to the annotators linked by
// the query's "searches text in" relations. The composed string is written back
// to the "Data X-Ray Query" attribute as a record of what actually ran. A
// criterion whose Collibra asset is retired (deleted in Data X-Ray) FAILS the
// rerun with a message naming it — silently dropping an AND criterion would
// broaden the search and import files the user never asked for.
//
// Headless mode: the nightly driver starts this workflow with the hidden
// 'headless' form property set to 'true'. Headless runs never throw (they are
// unattended) and skip the summary user task via a gateway; every abort path
// here sets rerunAborted=true so the downstream batch/retire tasks no-op.
//
// Produces the same batched work-list variables as the search workflow's
// import collector (importWorkList/importTotal/importCursor/hasMoreWork and
// the import*Count counters — sync_files_batch.groovy consumes them), plus:
//   previousFileIds (String)  – JSON array of file-asset UUIDs the query
//                               currently "returns" (retire pass diffs these)
//   rerunAborted    (Boolean) – true when the rerun could not run; batch and
//                               retire tasks then do nothing
//   rerunAbortReason(String)  – human-readable abort reason ('' when fine)
//   conditionName   (String)  – the query asset's name (for the summary form)
//   queryAssetId    (String)  – the query asset's UUID
//   rerunRetiredCount / rerunUnlinkedCount (Integer) – zeroed here, advanced
//                               by retire_orphans.groovy

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:rerun_common.groovy}}
// {{include:worklist.groovy}}

def ids = dxrModelIds()

// --- Headless normalization ----------------------------------------------------

// 'headless' arrives as a hidden form property ('true' when the nightly driver
// starts this workflow). Normalize it to a plain string so the BPMN gateway
// can test ${headless == 'true'} regardless of how it arrived.
boolean headless = (execution.getVariable('headless') ?: 'false').toString().trim() == 'true'
execution.setVariable('headless', headless ? 'true' : 'false')

// Every abort path funnels through here: interactive runs throw (the user sees
// why), headless runs record the reason and end cleanly (a nightly worker must
// never wedge — the batch/retire tasks check rerunAborted and no-op).
def abort = { String title, String message ->
    loggerApi.error("Rerun Data X-Ray Search aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    publishWorkList([], 0)
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

def cfg = readRequiredConfig([
    dataxrayUrl      : 'Data X-Ray Base URL',
    dataxrayAuthToken: 'Data X-Ray Auth Token (Bearer)',
])
if (!cfg.missing.isEmpty()) {
    abort('Rerun Data X-Ray Search misconfigured',
        'The Data X-Ray Base URL / Bearer token are not set on this workflow. Open its settings page and provide them.')
    return
}
def dataxrayUrl       = normalizeBaseUrl(cfg.config.dataxrayUrl)
def dataxrayAuthToken = cfg.config.dataxrayAuthToken

// --- The query asset ---------------------------------------------------------------

def queryId = item?.getId()
if (queryId == null) {
    abort('Rerun Data X-Ray Search', 'No query asset — this workflow must be started from an Unstructured Data Query asset.')
    return
}
def queryAsset = assetApi.getAsset(queryId)

// Hard type guard: this workflow is offered on EVERY asset page (Collibra
// can't scope a workflow with global start roles to one asset type —
// asset-type assignment rules are rejected with workflowWrongRoles). Started
// on anything but an Unstructured Data Query it must stop HERE: a file asset
// also carries "groups" relations to classifications, and without this guard
// the rebuild below would happily treat them as search criteria and import
// on the file's behalf.
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

// --- Compose the query (same composition rules as search_data_xray.groovy) ----------

def queryString = composeDxrQuery(criteria.labelNames, criteria.extractorNames, criteria.annotatorNames,
                                  criteria.filter, criteria.filterAnnotatorNames)
loggerApi.info("Rerun of '${conditionName}' — rebuilt Data X-Ray query: ${displayQuery(queryString)}")
recordQueryAttribute(queryId, ids, displayQuery(queryString))

// --- The files this query currently returns (for the retire diff) --------------------

def previousFileIds = collectPreviousFileIds(queryId, ids)

// --- Re-run the query against Data X-Ray ----------------------------------------------

def searchUrl = dxrFilesUrl(dataxrayUrl, queryString)

def FETCH_ATTEMPTS = 3
def workItems = []
int skippedNoId = 0
try {
    def collected = withDxrRetries(FETCH_ATTEMPTS, 5000, 'Data X-Ray fetch') {
        def items = []
        int skipped = 0
        streamDxrNdjson(searchUrl, dataxrayAuthToken, 120_000) { row ->
            def tuple = extractFileTuple(row, dataxrayUrl)
            if (tuple == null) { skipped++ } else { items << tuple }
        }
        return [items: items, skipped: skipped]
    }
    workItems = collected.items
    skippedNoId = collected.skipped
} catch (Exception fetchEx) {
    abort('Rerun Data X-Ray Search failed',
        "Could not fetch results from Data X-Ray for '${conditionName}' (${FETCH_ATTEMPTS} attempts): ${fetchEx.message}")
    return
}
if (skippedNoId > 0) {
    loggerApi.warn("Rerun of '${conditionName}': skipped ${skippedNoId} result row(s) without a usable file id")
}

// --- Instance-wide cap guard -----------------------------------------------------------

// Files this query already returns update in place — only genuinely new files
// grow the domain, so subtract the overlap (exact for THIS query; overlap with
// other queries' files still counts as new, which errs on the safe side).
int alreadyOurs = 0
workItems.each { tuple ->
    if (previousFileIds.contains(deterministicFileAssetId(tuple[0]).toString())) { alreadyOurs++ }
}
int filesDomainCount = countAssetsInDomain(ids.filesDomainId)
int projectedTotal = filesDomainCount + (workItems.size() - alreadyOurs)
if (projectedTotal > dxrMaxTotalFileAssets()) {
    abort('Rerun Data X-Ray Search refused',
        "Rerun of '${conditionName}' refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this rerun would add ${workItems.size() - alreadyOurs} more — the projected ${projectedTotal} exceeds the ${dxrMaxTotalFileAssets()} instance-wide limit.")
    return
}

// --- Resolve classifications and store the work list -------------------------------------

def classIndex = buildClassificationIndex(ids.classificationsDomainId, ids.dataxrayIdAttrTypeId)
int unresolvedClassifications = resolveTupleClassifications(workItems, classIndex)

execution.setVariable('rerunAborted', false)
execution.setVariable('rerunAbortReason', '')
int batchCount = publishWorkList(workItems, unresolvedClassifications)
execution.setVariable('previousFileIds', JsonOutput.toJson(previousFileIds as List))
execution.setVariable('rerunRetiredCount', 0)
execution.setVariable('rerunUnlinkedCount', 0)

loggerApi.info("Rerun of '${conditionName}' ready: ${workItems.size()} file(s) in ${batchCount} batch(es); ${previousFileIds.size()} previously returned, ${alreadyOurs} overlap; files domain holds ${filesDomainCount}")
