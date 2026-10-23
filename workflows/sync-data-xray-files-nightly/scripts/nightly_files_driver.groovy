// nightly_files_driver.groovy
//
// Nightly driver for the Data X-Ray file sync. Two phases:
//
// 1. Fan-out: finds every Unstructured Data Query asset tagged
//    'dataxray-keep-in-sync' (the flag set when a user ticks "keep in sync"
//    at import time) and starts one HEADLESS Rerun Data X-Ray Search instance
//    per query. Each rerun re-runs its search and re-syncs its file assets in
//    its own workflow instance with its own batched transactions — the driver
//    only fans out, so it stays tiny no matter how many queries are flagged.
//    Queries whose status is Obsolete are SKIPPED even if still tagged:
//    retiring/archiving a query is the supported way to pause its nightly
//    sync while keeping it restorable. A manual rerun from the asset page
//    still works on a retired query — that is explicit user intent.
//
// 2. Orphan sweep: sets every Data X-Ray File asset that NO query "returns"
//    any more to 'Not in any search' — wherever it lives (the scan is by asset
//    type, so files moved to another community are included). Deleting a saved
//    query asset in Collibra deletes its "returns" relations with it, leaving
//    the files it exclusively returned stranded — no rerun will ever retire
//    them because retire passes are per-query. The sweep closes that hole
//    (and also mops up assets whose relation add once failed mid-import).
//    Safety: the sweep only runs if the full relation scan succeeded — it
//    never parks based on a partial view. A status a person set (anything but
//    Candidate / Obsolete) is never changed. Orphans still Obsolete from a
//    release before v2.4 move to 'Not in any search' on the first run. If a
//    swept file reappears in any query's results, the rerun's batch stage
//    reactivates it (deterministic asset ids converge).
//
// This workflow is triggered by a timer start event (nightly at 02:30, after
// the 02:00 classification sync so renamed/retired classifications are
// up to date before queries are rebuilt). It runs unattended with no
// user/initiator, so it NEVER throws: a timer-triggered workflow that fails
// before its first async task is retried 3× by Collibra and then permanently
// disabled until redeployment. Every failure path logs and ends cleanly.
//
// This workflow has NO Data X-Ray connection settings of its own: it never
// calls Data X-Ray, so the same script ships in both editions. Each rerun
// instance it starts uses the connection settings configured on the Rerun
// Data X-Ray Search workflow — the Base URL / Bearer token on-prem, the Edge
// HTTP connection name in the Collibra Cloud + Edge edition.
//
// Process variables produced (audit only — there is no results form):
//   nightlyQueriesFound   (Integer) – flagged query assets discovered (incl. retired)
//   nightlyRerunsStarted  (Integer) – rerun instances started
//   nightlyFailedCount    (Integer) – queries whose rerun could not be started
//   nightlyRetiredSkipped (Integer) – flagged queries skipped because they are retired
//   nightlyOrphansRetired (Integer) – file assets set to 'Not in any search' because no query returns them

import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest
import com.collibra.dgc.core.api.dto.workflow.StartWorkflowInstancesRequest
import com.collibra.dgc.core.api.model.workflow.WorkflowBusinessItemType

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:file_batch.groovy}}

final String KEEP_IN_SYNC_TAG = 'dataxray-keep-in-sync'
final String RERUN_PROCESS_ID = 'rerunDataXraySearch'

announceDxrVersion('Sync Data X-Ray Files (Nightly)')
def ids = dxrModelIds()
def queryDomainId         = ids.queryDomainId
def returnsRelationTypeId = ids.returnsRelationTypeId
def OBSOLETE_STATUS_ID    = ids.obsoleteStatusId

def recordRunSummary = { int found, int started, int failed, int retiredSkipped, int orphansRetired, String note ->
    execution.setVariable('nightlyQueriesFound', found)
    execution.setVariable('nightlyRerunsStarted', started)
    execution.setVariable('nightlyFailedCount', failed)
    execution.setVariable('nightlyRetiredSkipped', retiredSkipped)
    execution.setVariable('nightlyOrphansRetired', orphansRetired)
    loggerApi.info("Sync Data X-Ray Files (Nightly): found=${found}, started=${started}, failed=${failed}, retiredSkipped=${retiredSkipped}, orphansRetired=${orphansRetired}${note ? ', ' + note : ''}")
}

// Park file assets that no query "returns" any more (see header, phase 2).
// Returns the number set to 'Not in any search'; any failure while building
// the FULL picture of returned files aborts the sweep — never park on a
// partial scan.
def sweepOrphanedFiles = {
    if (!statusApi.exists(ids.notInAnySearchStatusId)) {
        loggerApi.warn("Orphan sweep skipped: the status 'Not in any search' does not exist. Run Configure Data X-Ray Workflows once after upgrading.")
        return 0
    }
    def returnedFileIds = [] as Set
    def cursor = ''
    while (true) {
        def page = relationApi.findRelations(FindRelationsRequest.builder()
            .relationTypeId(returnsRelationTypeId)
            .limit(1000)
            .cursor(cursor)
            .build())
        page.getResults().each { rel -> returnedFileIds << rel.getTarget().getId() }
        cursor = page.getNextCursor()
        if (!cursor) break
    }

    int parked = 0
    eachFileAsset(ids) { asset ->
        if (returnedFileIds.contains(asset.getId())) { return }
        try {
            if (parkFileAsset(asset, ids, 'no query returns it (nightly sweep)')) { parked++ }
        } catch (Exception parkEx) {
            loggerApi.error("Failed to park orphaned file asset '${asset.getName()}' [${asset.getId()}]: ${parkEx.message}")
        }
    }
    return parked
}

// --- Phase 1: fan out one headless rerun per flagged query -----------------------

int found = 0
int started = 0
int failed = 0
int retiredSkipped = 0
def fanOutNote = ''

def rerunDefinitionId = null
try {
    def wd = workflowDefinitionApi.getWorkflowDefinitionByProcessId(RERUN_PROCESS_ID)
    if (wd == null) { throw new RuntimeException('definition lookup returned null') }
    rerunDefinitionId = wd.getId()
} catch (Exception defEx) {
    // Timer-triggered: never throw — log, skip the fan-out, still sweep below.
    loggerApi.error("Sync Data X-Ray Files (Nightly) fan-out skipped: the '${RERUN_PROCESS_ID}' workflow is not deployed (${defEx.message}). Deploy it and this job will pick it up tomorrow night.")
    fanOutNote = 'rerun workflow not deployed'
}

if (rerunDefinitionId != null) {
    def flaggedQueries = []
    try {
        def cursor = ''
        while (true) {
            def page = assetApi.findAssets(FindAssetsRequest.builder()
                .domainId(queryDomainId)
                .tagNames([KEEP_IN_SYNC_TAG])
                .limit(1000)
                .cursor(cursor)
                .build())
            flaggedQueries.addAll(page.getResults())
            cursor = page.getNextCursor()
            if (!cursor) break
        }
    } catch (Exception findEx) {
        loggerApi.error("Sync Data X-Ray Files (Nightly) fan-out skipped: could not list flagged queries: ${findEx.message}")
        fanOutNote = 'query lookup failed'
        flaggedQueries = []
    }

    found = flaggedQueries.size()

    // Retired (archived) queries keep their tag but are not synced — archiving
    // is the supported "pause" (see header). Reactivate the query to resume.
    def retiredFlagged = flaggedQueries.findAll { it.getStatus()?.getId() == OBSOLETE_STATUS_ID }
    retiredFlagged.each { query ->
        loggerApi.info("Skipping retired query '${query.getName()}' [${query.getId()}] — archived queries are not synced")
    }
    retiredSkipped = retiredFlagged.size()
    flaggedQueries = flaggedQueries.findAll { it.getStatus()?.getId() != OBSOLETE_STATUS_ID }

    flaggedQueries.each { query ->
        try {
            workflowInstanceApi.startWorkflowInstances(StartWorkflowInstancesRequest.builder()
                .workflowDefinitionId(rerunDefinitionId)
                .addBusinessItemId(query.getId())
                .businessItemType(WorkflowBusinessItemType.valueOf('ASSET'))
                .formProperties([headless: 'true'])
                .sendNotification(false)
                .build())
            started++
            loggerApi.info("Started headless rerun for query '${query.getName()}' [${query.getId()}]")
        } catch (Exception startEx) {
            failed++
            loggerApi.error("Failed to start rerun for query '${query.getName()}' [${query.getId()}]: ${startEx.message}")
        }
    }
}

// --- Phase 2: retire orphaned file assets ----------------------------------------

int orphansRetired = 0
try {
    orphansRetired = sweepOrphanedFiles()
} catch (Exception sweepEx) {
    // A failed relation/asset scan aborts the sweep — better to leave orphans
    // one more night than to retire based on a partial picture.
    loggerApi.error("Sync Data X-Ray Files (Nightly) orphan sweep skipped: ${sweepEx.message}")
}

recordRunSummary(found, started, failed, retiredSkipped, orphansRetired, fanOutNote)
