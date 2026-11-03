// nightly_files_driver.groovy
//
// Nightly driver for the DataXray file sync. Two phases:
//
// 1. Fan-out: finds every DataXray Sync Filter asset tagged
//    'dataxray-keep-in-sync' (the flag set when a user ticks "keep in sync"
//    at import time) and starts one HEADLESS Rerun DataXray Search instance
//    per query. Each rerun re-runs its search and re-syncs its file assets in
//    its own workflow instance with its own batched transactions — the driver
//    only fans out, so it stays tiny no matter how many queries are flagged.
//    Queries whose status is Obsolete are SKIPPED even if still tagged:
//    retiring/archiving a query is the supported way to pause its nightly
//    sync while keeping it restorable. A manual rerun from the asset page
//    still works on a retired query — that is explicit user intent.
//
// 2. Orphan sweep: sets every DataXray File asset that NO query "returns"
//    any more to 'Not in any search' — wherever it lives (the scan is by asset
//    type, so files moved to another community are included). Deleting a saved
//    query asset in Collibra deletes its "returns" relations with it, leaving
//    the files it exclusively returned stranded — no rerun will ever retire
//    them because retire passes are per-query. The sweep closes that hole
//    (and also mops up assets whose relation add once failed mid-import).
//    Safety: the sweep only runs if the full relation scan succeeded — it
//    never parks based on a partial view, and re-checks each asset's "returns"
//    relations just before parking it, because the reruns started in phase 1
//    run alongside the sweep and may have created or linked it since the scan
//    (otherwise a brand-new asset could be parked with a live link, TRC
//    stories #28/#29). A status a person set (anything but
//    Candidate / Obsolete) is never changed. Orphans still Obsolete from a
//    release before v2.4 move to 'Not in any search' on the first run. If a
//    swept file reappears in any query's results, the rerun's batch stage
//    reactivates it (deterministic asset ids converge).
//
// 3. DataXray Sync (v2.10): the same scan sets every file asset's DataXray
//    Sync attribute to "Kept in sync nightly" when a sync filter phase 1
//    reruns returns it, else "One-time import" — the catch-all for changes no
//    rerun sees (a keep-in-sync tag removed by hand, a sync filter archived or
//    deleted). Reruns keep it current for the files they touch. Only changed
//    values are written, each after re-reading the file's links (the reruns
//    phase 1 started run alongside).
//
// This workflow is triggered by a timer start event (nightly at 02:30, after
// the 02:00 classification sync so renamed/retired classifications are
// up to date before queries are rebuilt). It runs unattended with no
// user/initiator, so it NEVER throws: a timer-triggered workflow that fails
// before its first async task is retried 3× by Collibra and then permanently
// disabled until redeployment. Every failure path logs and ends cleanly.
//
// This script never calls DataXray, so it ships unchanged in both editions.
// Each rerun instance it starts uses the connection settings configured on the
// Search DataXray workflow (v2.11; the rerun has none of its own) — the Base
// URL / Bearer token on-prem, the Edge HTTP connection name in the Collibra
// Cloud + Edge edition. The Edge
// edition then runs a third phase after this script, with its own connection
// name setting: the DataXray existence check (workflow.edge.bpmn.template,
// shared/file_exists_edge.groovy), which sets 'Not in DataXray' and refreshes
// the files DataXray still has. The Cloud + Edge rerun runs the same check
// for the files it parks, so by the time the nightly runs most of them are
// settled; the nightly covers the rest (files parked by the sweep, files
// back in DataXray, checks that could not complete).
//
// Process variables produced (audit only — there is no results form):
//   nightlyQueriesFound   (Integer) – flagged query assets discovered (incl. retired)
//   nightlyRerunsStarted  (Integer) – rerun instances started
//   nightlyFailedCount    (Integer) – queries whose rerun could not be started
//   nightlyRetiredSkipped (Integer) – flagged queries skipped because they are retired
//   nightlyOrphansRetired (Integer) – file assets set to 'Not in any search' because no query returns them
//   nightlySyncModeUpdated (Integer) – file assets whose DataXray Sync value changed

import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest
import com.collibra.dgc.core.api.dto.workflow.StartWorkflowInstancesRequest
import com.collibra.dgc.core.api.model.workflow.WorkflowBusinessItemType

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:file_batch.groovy}}

final String KEEP_IN_SYNC_TAG = dxrKeepInSyncTag()
final String RERUN_PROCESS_ID = 'rerunDataXraySearch'

announceDxrVersion('Sync DataXray Files (Nightly)')
def ids = dxrModelIds()
def queryDomainId         = ids.queryDomainId
def returnsRelationTypeId = ids.returnsRelationTypeId
def OBSOLETE_STATUS_ID    = ids.obsoleteStatusId

def recordRunSummary = { int found, int started, int failed, int retiredSkipped, int orphansRetired, int syncModeUpdated, String note ->
    execution.setVariable('nightlyQueriesFound', found)
    execution.setVariable('nightlyRerunsStarted', started)
    execution.setVariable('nightlyFailedCount', failed)
    execution.setVariable('nightlyRetiredSkipped', retiredSkipped)
    execution.setVariable('nightlyOrphansRetired', orphansRetired)
    execution.setVariable('nightlySyncModeUpdated', syncModeUpdated)
    loggerApi.info("Sync DataXray Files (Nightly): found=${found}, started=${started}, failed=${failed}, retiredSkipped=${retiredSkipped}, orphansRetired=${orphansRetired}, syncModeUpdated=${syncModeUpdated}${note ? ', ' + note : ''}")
}

// Park file assets that no query "returns" any more (see header, phase 2), and
// bring every file asset's DataXray Sync value up to date (phase 3) when
// `syncedQueryIds` (the sync filters phase 1 reruns) is known. Returns
// [parked:, syncModeUpdated:]; any failure while building the FULL picture of
// returned files aborts both — never act on a partial scan.
def sweepOrphanedFiles = { Set syncedQueryIds ->
    boolean parkOn = statusApi.exists(ids.notInAnySearchStatusId)
    if (!parkOn) {
        loggerApi.warn("Orphan sweep skipped: the status 'Not in any search' does not exist. Run Configure DataXray Workflows once after upgrading.")
    }
    boolean syncModeOn = syncedQueryIds != null && syncModeAttrOn()
    if (!parkOn && !syncModeOn) { return [parked: 0, syncModeUpdated: 0] }
    def returnedBy = [:]   // file asset id → ids of the sync filters that return it
    def cursor = ''
    while (true) {
        def page = relationApi.findRelations(FindRelationsRequest.builder()
            .relationTypeId(returnsRelationTypeId)
            .limit(1000)
            .cursor(cursor)
            .build())
        page.getResults().each { rel ->
            returnedBy.computeIfAbsent(rel.getTarget().getId()) { [] as Set } << rel.getSource().getId()
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    def currentModes = syncModeOn ? attributeValuesOfType(ids.syncModeAttrTypeId) : [:]
    def modeFor = { Collection sources -> sources.any { syncedQueryIds.contains(it) } ? dxrSyncModeKeptInSync() : dxrSyncModeOneTime() }

    int parked = 0
    int syncModeUpdated = 0
    eachFileAsset(ids) { asset ->
        if (syncModeOn && currentModes[asset.getId().toString()] != modeFor(returnedBy[asset.getId()] ?: [])) {
            try {
                // A rerun started above may have linked the file since the scan.
                def mode = modeFor(relationIdsByFarEnd(returnsRelationTypeId, null, asset.getId()).keySet())
                if (currentModes[asset.getId().toString()] != mode) {
                    writeFileSyncMode(asset.getId(), mode, ids)
                    syncModeUpdated++
                }
            } catch (Exception modeEx) {
                loggerApi.error("Failed to update DataXray Sync on file asset '${asset.getName()}' [${asset.getId()}]: ${modeEx.message}")
            }
        }
        if (!parkOn || returnedBy.containsKey(asset.getId())) { return }
        def sid = asset.getStatus()?.getId()
        // Nothing to change (already parked, or a person's status): no further lookups.
        if (!isIntegrationOwnedFileStatus(sid, ids) || sid == ids.notInAnySearchStatusId || sid == ids.notInDataXrayStatusId) { return }
        try {
            // The reruns started above run alongside this sweep: a file asset one
            // of them created or linked after the relation scan is not an orphan.
            // Re-ask for this one asset before changing it.
            def returnedNow = relationApi.findRelations(FindRelationsRequest.builder()
                .relationTypeId(returnsRelationTypeId)
                .targetId(asset.getId())
                .limit(1)
                .build())
            if (!returnedNow.getResults().isEmpty()) { return }
            if (parkFileAsset(assetApi.getAsset(asset.getId()), ids, 'no query returns it (nightly sweep)')) { parked++ }
        } catch (Exception parkEx) {
            loggerApi.error("Failed to park orphaned file asset '${asset.getName()}' [${asset.getId()}]: ${parkEx.message}")
        }
    }
    return [parked: parked, syncModeUpdated: syncModeUpdated]
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
    loggerApi.error("Sync DataXray Files (Nightly) fan-out skipped: the '${RERUN_PROCESS_ID}' workflow is not deployed (${defEx.message}). Deploy it and this job will pick it up tomorrow night.")
    fanOutNote = 'rerun workflow not deployed'
}

// The flagged sync filters are listed even when the rerun workflow is missing:
// the DataXray Sync pass (phase 3) needs them. null = could not be listed.
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
    loggerApi.error("Sync DataXray Files (Nightly) fan-out skipped: could not list flagged queries: ${findEx.message}")
    fanOutNote = 'query lookup failed'
    flaggedQueries = null
}

def syncedQueryIds = null
if (flaggedQueries != null) {
    found = flaggedQueries.size()
    // Retired (archived) queries keep their tag but are not synced — archiving
    // is the supported "pause" (see header). Reactivate the query to resume.
    def retiredFlagged = flaggedQueries.findAll { it.getStatus()?.getId() == OBSOLETE_STATUS_ID }
    retiredFlagged.each { query ->
        loggerApi.info("Skipping retired query '${query.getName()}' [${query.getId()}] — archived queries are not synced")
    }
    retiredSkipped = retiredFlagged.size()
    flaggedQueries = flaggedQueries.findAll { it.getStatus()?.getId() != OBSOLETE_STATUS_ID }
    syncedQueryIds = flaggedQueries.collect { it.getId() } as Set
}

if (rerunDefinitionId != null && flaggedQueries != null) {
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
int syncModeUpdated = 0
try {
    def swept = sweepOrphanedFiles(syncedQueryIds)
    orphansRetired = swept.parked
    syncModeUpdated = swept.syncModeUpdated
} catch (Exception sweepEx) {
    // A failed relation/asset scan aborts the sweep — better to leave orphans
    // one more night than to retire based on a partial picture.
    loggerApi.error("Sync DataXray Files (Nightly) orphan sweep skipped: ${sweepEx.message}")
}

recordRunSummary(found, started, failed, retiredSkipped, orphansRetired, syncModeUpdated, fanOutNote)
