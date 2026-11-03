// rerun_count_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after the count probe's External API task (interactive runs
// only, v2.10): reads how many files the search matches now and publishes the
// Rerun Options task's variables (rerun_common.groovy). A failed request is
// retried twice (rerunCountGateway loops back to the External API task); then,
// or when the search matches more than DataXray can page through, the run is
// aborted (rerunAborted): it skips the options task and shows the reason on
// the Rerun Summary. Never throws.
//
// Reads:  the External API task's response, previousFileIds, filesDomainCount,
//         rerunTagKeepInSync, rerunTagNoAutoImport, conditionName
// Writes: hasMoreWork (retry loop), rerunAborted/rerunAbortReason, the Rerun
//         Options form's variables

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:rerun_common.groovy}}

def conditionName = (execution.getVariable('conditionName') ?: '').toString()

def abortRun = { String message ->
    loggerApi.error("Rerun of '${conditionName}' aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('dxrFetchComplete', false)
}

def r = readEdgeCountProbe()
if (!r.ok) {
    if (edgeRetry("Rerun of '${conditionName}': counting the matching files".toString(), r.error)) { return }
    abortRun("Could not reach DataXray to rerun '${conditionName}': ${r.error}".toString())
    return
}
execution.setVariable('hasMoreWork', false)

int total = r.total as int
if (total > (r.maxResultWindow as int)) {
    abortRun("Rerun of '${conditionName}' refused: it matches ${total} file(s), more than the ${r.maxResultWindow} results DataXray can page through in one query.".toString())
    return
}

// Upper bound for new file assets: today's matches minus the files the search
// returned before (some of those may have left the results, so "up to").
int growth = Math.max(0, total - previousFileCount())
int filesDomainCount = (execution.getVariable('filesDomainCount') ?: 0) as int
publishRerunOptions(computeRerunFeasibility(total, growth, filesDomainCount, false), total, growth, false,
                    execution.getVariable('rerunTagKeepInSync') == true,
                    execution.getVariable('rerunTagNoAutoImport') != true)
loggerApi.info("Rerun of '${conditionName}': DataXray matches ${total} file(s), up to ${growth} new; waiting for the Rerun Options task")
