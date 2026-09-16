// sync_apply_edge.groovy  (interactive; Collibra Cloud + Edge variant)
//
// ASYNC task after the External API task: parse the catalogue and sync it into
// Collibra (shared/classification_sync.groovy). Never throws — a failure is
// recorded in syncFailed/dxrErrorMessage and the "Sync Failed" form shows it.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:classification_sync.groovy}}

def fail = { String msg ->
    loggerApi.error("Data X-Ray sync failed: ${msg}")
    execution.setVariable('syncFailed', true)
    execution.setVariable('dxrErrorMessage', msg)
    execution.setVariable('syncFailuresDisplay', msg)
}

def r = readEdgeResponse()
if (!r.ok) {
    fail("Could not fetch classifications from Data X-Ray: ${r.error}. Check the Edge HTTP connection '${execution.getVariable('dataxrayConnectionName')}'.".toString())
    return
}
def classifications
try {
    classifications = parseClassificationsBody(r.body)
} catch (Exception parseEx) {
    fail(parseEx.message)
    return
}
loggerApi.info("Data X-Ray returned ${classifications.size()} classification(s); syncing into Collibra")

def res = syncClassificationCatalog(classifications, (execution.getVariable('dataxrayUrl') ?: '').toString())

execution.setVariable('syncCreatedCount', res.created)
execution.setVariable('syncUpdatedCount', res.updated)
execution.setVariable('syncRetiredCount', res.retired)
execution.setVariable('syncSkippedCount', res.skipped)
execution.setVariable('syncFailedCount',  res.failed)
execution.setVariable('syncFailures',     res.failures.join('; '))
execution.setVariable('syncFailuresDisplay', res.failures.isEmpty() ? 'None' : res.failures.join('; '))

if (res.created == 0 && res.updated == 0 && res.failed > 0) {
    fail("All ${res.failed} classification(s) failed to sync. First error: ${res.failures[0]}".toString())
}
