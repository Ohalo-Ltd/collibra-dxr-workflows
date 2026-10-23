// sync_classifications.groovy  (nightly; on-prem variant: direct HTTPS to DataXray)
//
// Pulls classifications/annotators/extractors/labels from the DataXray API
// and mirrors them as Collibra assets in the classifications domain. The sync
// semantics (upsert by DataXray ID only, freeing names held by retired
// assets, name disambiguation, retire-never-delete) live in
// shared/classification_sync.groovy.
//
// This workflow is triggered by a timer start event (nightly at 02:00), so it
// runs unattended with no user/initiator. On misconfiguration or a transient
// DataXray outage it logs the reason and ends cleanly rather than throwing:
// a timer-triggered workflow that fails before its first async task is retried
// 3× by Collibra and then permanently disabled until redeployment.
//
// The only configuration variables are the per-instance secrets (Base URL and
// Bearer token), set by an admin on the workflow settings page; until the token
// is supplied the nightly run skips cleanly. All operating-model IDs are fixed
// constants (shared/dxr_model.groovy), created by the configure-data-xray-workflows
// admin workflow with exactly those UUIDs.
//
// Process variables produced (audit only — there is no results form):
//   syncCreatedCount / syncUpdatedCount / syncRetiredCount / syncSkippedCount /
//   syncFailedCount (Integer), syncFailures (String)

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:classification_sync.groovy}}

// --- Read & validate workflow configuration variables -----------------------

announceDxrVersion('Sync DataXray Classification (Nightly)')
def cfg = readRequiredConfig([
    dataxrayUrl      : 'DataXray Base URL',
    dataxrayAuthToken: 'DataXray Auth Token (Bearer)',
])
if (!cfg.missing.isEmpty()) {
    def detailMsg = "Skipping Sync DataXray Classification (Nightly) — the following workflow configuration variable(s) are not set:${missingConfigBullets(cfg.missing)}\n\nOpen the workflow's settings page and provide a value for each; the next nightly run will pick them up automatically."
    // Timer-triggered: end cleanly rather than throw. A WorkflowException here
    // (before the first async task) would be retried 3× by Collibra and then
    // permanently disable the schedule until redeployment.
    loggerApi.error(detailMsg)
    recordRunSummary(0, 0, 0, 0, 0, "misconfigured: ${cfg.missing.size()} configuration variable(s) unset")
    return
}
def dataxrayUrl       = cfg.config.dataxrayUrl
def dataxrayAuthToken = cfg.config.dataxrayAuthToken

// --- Fetch classifications from DataXray ----------------------------------

def classifications
try {
    classifications = parseClassificationsBody(fetchDxrText(dataxrayUrl + '/api/v1/classifications', dataxrayAuthToken, 60_000))
} catch (Exception fetchEx) {
    // Timer-triggered: a transient DataXray outage must not throw (see above)
    // — log and end cleanly so the schedule survives and retries next night.
    loggerApi.error("Failed to fetch classifications from DataXray: ${fetchEx.message}")
    recordRunSummary(0, 0, 0, 0, 0, "fetch failed: ${fetchEx.message}")
    return
}
loggerApi.info("DataXray returned ${classifications.size()} classification(s); syncing into Collibra")

// --- Sync ---------------------------------------------------------------------

def r = syncClassificationCatalog(classifications, dataxrayUrl)

// --- Report -----------------------------------------------------------------

recordRunSummary(r.created, r.updated, r.retired, r.skipped, r.failed, r.failures.join('; '))

// If nothing was created or updated and at least one item failed, log it as an
// error for dgc.log. Timer-triggered: don't throw — a thrown WorkflowException
// before the first async task is retried 3× and then disables the schedule.
if (r.created == 0 && r.updated == 0 && r.failed > 0) {
    loggerApi.error("DataXray sync run failed: all ${r.failed} classification(s) failed to sync. First error: ${r.failures[0]}")
}

// --- Helpers ---------------------------------------------------------------

// Persist the run outcome to process variables for audit / downstream tasks.
def recordRunSummary(int created, int updated, int retired, int skipped, int failed, String failuresJoined) {
    execution.setVariable('syncCreatedCount', created)
    execution.setVariable('syncUpdatedCount', updated)
    execution.setVariable('syncRetiredCount', retired)
    execution.setVariable('syncSkippedCount', skipped)
    execution.setVariable('syncFailedCount',  failed)
    execution.setVariable('syncFailures',     failuresJoined)
}
