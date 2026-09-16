// sync_prepare_edge.groovy  (interactive; Collibra Cloud + Edge variant)
//
// Synchronous first task: validate the configuration (errors surface in the
// start dialog) and arm GET /api/v1/classifications for the External API task.

import com.collibra.dgc.workflow.api.exception.WorkflowException

// {{include:dxr_config.groovy}}
// {{include:dxr_edge.groovy}}

def connectionName = (execution.getVariable('dataxrayConnectionName') ?: '').toString().trim()
if (connectionName.isEmpty() || isPlaceholderValue(connectionName)) {
    def detailMsg = "Cannot run Sync Data X-Ray Classifications — the 'Edge HTTP connection name (Data X-Ray)' configuration variable is not set.\n\nOpen the workflow's settings page and enter the name of the HTTP connection to Data X-Ray on your Edge site, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('Data X-Ray sync misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
}
def dataxrayUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
if (isPlaceholderValue(dataxrayUrl)) { dataxrayUrl = '' }
execution.setVariable('dataxrayUrl', dataxrayUrl)

execution.setVariable('syncFailed', false)
execution.setVariable('dxrErrorMessage', '')
edgeResetRetries()
armEdgeRequest('GET', '/api/v1/classifications', '')
loggerApi.info("Sync Data X-Ray Classifications via Edge connection '${connectionName}': request armed")
