// rerun_collector.groovy  (on-prem variant: direct HTTPS to DataXray)
//
// First stage of "Rerun DataXray Search" — an ASSET-scoped workflow started
// from a saved DataXray Sync Filter asset (or headlessly, per query, by the
// nightly file sync driver).
//
// The saved criteria are rebuilt from the query asset (shared/rerun_common.groovy):
// its "groups" relations to classification assets, using their CURRENT names,
// plus the "Annotated Text Filter" attribute scoped to the annotators linked by
// the query's "searches text in" relations. The composed string is written back
// to the "DataXray Query" attribute as a record of what actually ran. A
// criterion whose Collibra asset is retired (deleted in DataXray) FAILS the
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
//   rerunRetireSkipped / rerunRetireSkipReason – false / '' here, set by
//                               retire_orphans.groovy when it skips (summary form)
//   rerunExistsRan / rerunExistsNoted / rerunExistsNote, exists* counters –
//                               false / '' / 0: only the Cloud + Edge rerun
//                               runs the DataXray existence check
//   rerunTagKeepInSync / rerunTagNoAutoImport (Boolean) – the sync filter's
//                               tags as found
//   rerunNewFileCount / rerunSkippedNoId / filesDomainCount (Integer) – for
//                               rerun_apply_options.groovy's limit checks
//   the Rerun Options form's variables (publishRerunOptions, rerun_common.groovy)
// Nothing is written to Collibra beyond the DataXray Query attribute: the
// Rerun Options task comes next (interactive runs; v2.10), and
// rerun_apply_options.groovy then applies the options, checks the limits and
// refreshes the sync filter's "Files" preview.

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_query.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:rerun_common.groovy}}
// {{include:worklist.groovy}}

announceDxrVersion('Rerun DataXray Search')
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
    loggerApi.error("Rerun DataXray Search aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    publishWorkList([], 0)
    execution.setVariable('previousFileIds', '[]')
    execution.setVariable('rerunRetiredCount', 0)
    execution.setVariable('rerunUnlinkedCount', 0)
    execution.setVariable('rerunRetireSkipped', false)
    execution.setVariable('rerunRetireSkipReason', '')
    zeroRerunExistsSummary()
    execution.setVariable('rerunTagKeepInSync', false)
    execution.setVariable('rerunTagNoAutoImport', false)
    execution.setVariable('rerunNewFileCount', 0)
    execution.setVariable('rerunSkippedNoId', 0)
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

def cfg = readRequiredConfig([
    dataxrayUrl      : 'DataXray Base URL',
    dataxrayAuthToken: 'DataXray Auth Token (Bearer)',
])
if (!cfg.missing.isEmpty()) {
    abort('Rerun DataXray Search misconfigured',
        'The DataXray Base URL / Bearer token are not set on this workflow. Open its settings page and provide them.')
    return
}
def dataxrayUrl       = normalizeBaseUrl(cfg.config.dataxrayUrl)
def dataxrayAuthToken = cfg.config.dataxrayAuthToken

// The 'Not in any search' status and the "formerly returns" relation type
// come from Configure DataXray Workflows (v2.4+): refuse to run without them
// rather than half-apply the new lifecycle.
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

// Hard type guard: this workflow is offered on EVERY asset page (Collibra
// can't scope a workflow with global start roles to one asset type —
// asset-type assignment rules are rejected with workflowWrongRoles). Started
// on anything but a DataXray Sync Filter it must stop HERE: a file asset
// may still carry "groups" relations to classifications (links from before
// v2.11, or a person's), and without this guard
// the rebuild below would happily treat them as search criteria and import
// on the file's behalf.
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

// --- Compose the query (same composition rules as search_data_xray.groovy) ----------

def queryString = composeDxrQuery(criteria.labelNames, criteria.extractorNames, criteria.annotatorNames,
                                  criteria.filter, criteria.filterAnnotatorNames)
loggerApi.info("Rerun of '${conditionName}' — rebuilt DataXray query: ${displayQuery(queryString)}")
// Written to the DataXray Query attribute once the options are applied (applyRerunOptions).
execution.setVariable('rerunQueryDisplay', displayQuery(queryString))

// --- The files this query currently returns (for the retire diff) --------------------

def previousFileIds = collectPreviousFileIds(queryId, ids)

// --- Re-run the query against DataXray ----------------------------------------------

def searchUrl = dxrFilesUrl(dataxrayUrl, queryString)

def FETCH_ATTEMPTS = 3
def workItems = []
int skippedNoId = 0
try {
    def collected = withDxrRetries(FETCH_ATTEMPTS, 5000, 'DataXray fetch') {
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
    abort('Rerun DataXray Search failed',
        "Could not fetch results from DataXray for '${conditionName}' (${FETCH_ATTEMPTS} attempts): ${fetchEx.message}")
    return
}
if (skippedNoId > 0) {
    loggerApi.warn("Rerun of '${conditionName}': skipped ${skippedNoId} result row(s) without a usable file id")
}

// --- What creating assets for the new files would mean ----------------------------------

// Files this query already returns update in place — only genuinely new files
// grow the domain, so subtract the overlap (exact for THIS query; overlap with
// other queries' files still counts as new, which errs on the safe side). The
// limits themselves are checked once the options are known
// (rerun_apply_options.groovy): refreshing only the files already imported is
// always allowed.
int alreadyOurs = 0
workItems.each { tuple ->
    if (previousFileIds.contains(deterministicFileAssetId(tuple[0]).toString())) { alreadyOurs++ }
}
int filesDomainCount = countFileAssets(ids)
int newFiles = workItems.size() - alreadyOurs
execution.setVariable('rerunNewFileCount', newFiles)
execution.setVariable('rerunSkippedNoId', skippedNoId)
execution.setVariable('filesDomainCount', filesDomainCount)

// --- Resolve classifications and store the work list -------------------------------------

def classIndex = buildClassificationIndex(ids)
int unresolvedClassifications = resolveTupleClassifications(workItems, classIndex)

execution.setVariable('rerunAborted', false)
execution.setVariable('rerunAbortReason', '')
int batchCount = publishWorkList(workItems, unresolvedClassifications)
execution.setVariable('previousFileIds', JsonOutput.toJson(previousFileIds as List))
execution.setVariable('rerunCreateNew', !tagNoAutoImport)
publishRerunOptions(computeRerunFeasibility(workItems.size() + skippedNoId, newFiles, filesDomainCount, true),
                    workItems.size() + skippedNoId, newFiles, true, tagKeepInSync, !tagNoAutoImport)
execution.setVariable('rerunRetiredCount', 0)
execution.setVariable('rerunUnlinkedCount', 0)
execution.setVariable('rerunRetireSkipped', false)
execution.setVariable('rerunRetireSkipReason', '')
zeroRerunExistsSummary()   // on-prem: no existence check (no object ids), the summary rows stay hidden

loggerApi.info("Rerun of '${conditionName}' ready: ${workItems.size()} file(s) in ${batchCount} batch(es); ${previousFileIds.size()} previously returned, ${alreadyOurs} overlap; files domain holds ${filesDomainCount}")
