// sync_apply_edge.groovy  (nightly; Collibra Cloud + Edge variant)
//
// ASYNC task after the External API task: parse the catalogue and sync it into
// Collibra (shared/classification_sync.groovy). Timer-triggered and unattended,
// so it never throws — outcomes go to the run-summary variables and dgc.log.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:classification_sync.groovy}}

def r = readEdgeResponse()
if (!r.ok) {
    loggerApi.error("Failed to fetch classifications from Data X-Ray: ${r.error}")
    recordRunSummary(0, 0, 0, 0, 0, "fetch failed: ${r.error}".toString())
    return
}
def classifications
try {
    classifications = parseClassificationsBody(r.body)
} catch (Exception parseEx) {
    loggerApi.error("Failed to parse classifications from Data X-Ray: ${parseEx.message}")
    recordRunSummary(0, 0, 0, 0, 0, "fetch failed: ${parseEx.message}".toString())
    return
}
loggerApi.info("Data X-Ray returned ${classifications.size()} classification(s); syncing into Collibra")

def res = syncClassificationCatalog(classifications, (execution.getVariable('dataxrayUrl') ?: '').toString())
recordRunSummary(res.created, res.updated, res.retired, res.skipped, res.failed, res.failures.join('; '))

if (res.created == 0 && res.updated == 0 && res.failed > 0) {
    loggerApi.error("Data X-Ray sync run failed: all ${res.failed} classification(s) failed to sync. First error: ${res.failures[0]}")
}

// Persist the run outcome to process variables for audit / downstream tasks.
def recordRunSummary(int created, int updated, int retired, int skipped, int failed, String failuresJoined) {
    execution.setVariable('syncCreatedCount', created)
    execution.setVariable('syncUpdatedCount', updated)
    execution.setVariable('syncRetiredCount', retired)
    execution.setVariable('syncSkippedCount', skipped)
    execution.setVariable('syncFailedCount',  failed)
    execution.setVariable('syncFailures',     failuresJoined)
}
