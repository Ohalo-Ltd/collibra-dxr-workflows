// sync_prepare.groovy  (interactive; both editions)
//
// Synchronous first task: validate the configuration (errors surface in the
// start dialog), index what Collibra already knows about the classifications
// (public uuid + numeric index id per asset) and arm the first catalogue
// request of the loop (shared/dxr_catalog_sync.groovy).

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:dxr_catalog_sync.groovy}}

def ids = dxrModelIds()
def transport = checkDxrTransport()
if (!transport.ok) {
    def detailMsg = transportMisconfiguredMessage('Cannot run Sync Data X-Ray Classifications', transport) + ', then start the workflow again.'
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
execution.setVariable('classIndex', JsonOutput.toJson(buildSearchClassificationIndex(ids)))
startCatalogSync()
loggerApi.info("Sync Data X-Ray Classifications via ${transport.via}: catalogue requests armed")
