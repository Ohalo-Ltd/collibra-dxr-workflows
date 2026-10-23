// sync_classifications.groovy  (interactive; on-prem variant: direct HTTPS to DataXray)
//
// Pulls classifications/annotators/extractors/labels from the DataXray API
// and mirrors them as Collibra assets in the classifications domain. The sync
// semantics (upsert by DataXray ID only, freeing names held by retired
// assets, name disambiguation, retire-never-delete) live in
// shared/classification_sync.groovy.
//
// The only configuration variables are the per-instance secrets (Base URL and
// Bearer token), set by an admin on the workflow settings page. All
// operating-model IDs are fixed constants (shared/dxr_model.groovy), created by
// the configure-data-xray-workflows admin workflow with exactly those UUIDs.
//
// Process variables produced (for the results form / audit):
//   syncCreatedCount (Integer) – assets newly created
//   syncUpdatedCount (Integer) – existing assets reused (attributes resynced)
//   syncRetiredCount (Integer) – tagged assets retired because they were no longer in DataXray
//   syncSkippedCount (Integer) – classifications skipped (missing name, unknown type, etc.)
//   syncFailedCount  (Integer) – per-classification errors during upsert
//   syncFailures     (String)  – semicolon-joined "<name>: <error>" list, may be empty
//   syncFailuresDisplay (String) – same as syncFailures but the literal "None"
//       when empty, so the results form can render it without conditional logic.

import com.collibra.dgc.workflow.api.exception.WorkflowException

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:classification_sync.groovy}}

// --- Read & validate workflow configuration variables -----------------------

announceDxrVersion('Sync DataXray Classifications')
def cfg = readRequiredConfig([
    dataxrayUrl      : 'DataXray Base URL',
    dataxrayAuthToken: 'DataXray Auth Token (Bearer)',
])
if (!cfg.missing.isEmpty()) {
    def detailMsg = "Cannot run Sync DataXray Classifications — the following workflow configuration variable(s) are not set:${missingConfigBullets(cfg.missing)}\n\nOpen the workflow's settings page and provide a value for each, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('DataXray sync misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
}
def dataxrayUrl       = cfg.config.dataxrayUrl
def dataxrayAuthToken = cfg.config.dataxrayAuthToken

// --- Fetch classifications from DataXray ----------------------------------

def classifications
try {
    classifications = parseClassificationsBody(fetchDxrText(dataxrayUrl + '/api/v1/classifications', dataxrayAuthToken, 60_000))
} catch (Exception fetchEx) {
    loggerApi.error("Failed to fetch classifications from DataXray: ${fetchEx.message}")
    def wf = new WorkflowException("DataXray classification fetch failed: ${fetchEx.message}", fetchEx)
    wf.setTitleMessage('DataXray sync failed')
    wf.setUserMessage("Could not fetch classifications from DataXray: ${fetchEx.message}")
    throw wf
}
loggerApi.info("DataXray returned ${classifications.size()} classification(s); syncing into Collibra")

// --- Sync ---------------------------------------------------------------------

def r = syncClassificationCatalog(classifications, dataxrayUrl)

// --- Report -----------------------------------------------------------------

execution.setVariable('syncCreatedCount', r.created)
execution.setVariable('syncUpdatedCount', r.updated)
execution.setVariable('syncRetiredCount', r.retired)
execution.setVariable('syncSkippedCount', r.skipped)
execution.setVariable('syncFailedCount',  r.failed)
execution.setVariable('syncFailures',     r.failures.join('; '))
execution.setVariable('syncFailuresDisplay', r.failures.isEmpty() ? 'None' : r.failures.join('; '))

// If nothing was created or updated and at least one item failed, surface the
// run as a failure in the Collibra UI.
if (r.created == 0 && r.updated == 0 && r.failed > 0) {
    def wf = new WorkflowException("All ${r.failed} classification(s) failed to sync. First error: ${r.failures[0]}")
    wf.setTitleMessage('DataXray sync failed')
    wf.setUserMessage("All ${r.failed} classification(s) failed to sync. First error: ${r.failures[0]}")
    throw wf
}
