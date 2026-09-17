// sync_prepare_edge.groovy  (interactive; Collibra Cloud + Edge variant)
//
// Synchronous first task: validate the configuration (errors surface in the
// start dialog), index what Collibra already knows about the classifications
// (public uuid + numeric index id per asset) and arm the first catalogue
// request of the loop (shared/dxr_edge_sync.groovy).

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:dxr_edge_sync.groovy}}

def ids = dxrModelIds()
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
execution.setVariable('classIndex', JsonOutput.toJson(buildEdgeClassificationIndex(ids)))
startEdgeSync()
loggerApi.info("Sync Data X-Ray Classifications via Edge connection '${connectionName}': catalogue requests armed")
