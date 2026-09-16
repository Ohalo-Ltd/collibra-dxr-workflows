// sync_prepare_edge.groovy  (nightly; Collibra Cloud + Edge variant)
//
// Synchronous first task of the timer workflow. Never throws: a timer-triggered
// workflow that fails before its first async task is retried 3× by Collibra and
// then permanently disabled. Misconfiguration records an empty run summary and
// sets syncSkip=true so the gateway ends the run before the External API task.

// {{include:dxr_config.groovy}}
// {{include:dxr_edge.groovy}}

def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
execution.setVariable('syncSkip', false)
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    def detailMsg = "Skipping Sync Data X-Ray Classification (Nightly) — the 'Edge HTTP connection name (Data X-Ray)' configuration variable is not set.\n\nOpen the workflow's settings page and enter the name of the HTTP connection to Data X-Ray on your Edge site; the next nightly run will pick it up automatically."
    loggerApi.error(detailMsg)
    recordRunSummary(0, 0, 0, 0, 0, 'misconfigured: Edge HTTP connection name unset')
    execution.setVariable('syncSkip', true)
    return
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)

edgeResetRetries()
armEdgeRequest('GET', '/api/v1/classifications', '')
loggerApi.info("Sync Data X-Ray Classification (Nightly) via Edge connection '${connectionName}': request armed")

// Persist the run outcome to process variables for audit / downstream tasks.
def recordRunSummary(int created, int updated, int retired, int skipped, int failed, String failuresJoined) {
    execution.setVariable('syncCreatedCount', created)
    execution.setVariable('syncUpdatedCount', updated)
    execution.setVariable('syncRetiredCount', retired)
    execution.setVariable('syncSkippedCount', skipped)
    execution.setVariable('syncFailedCount',  failed)
    execution.setVariable('syncFailures',     failuresJoined)
}
