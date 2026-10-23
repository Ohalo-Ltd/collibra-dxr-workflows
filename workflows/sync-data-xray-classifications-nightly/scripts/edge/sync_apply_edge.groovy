// sync_apply_edge.groovy  (nightly; Collibra Cloud + Edge variant)
//
// ASYNC task after the catalogue loop: assemble the catalogue and sync it into
// Collibra (shared/classification_sync.groovy), stamping each asset's numeric
// DataXray Index ID. Timer-triggered and unattended, so it never throws —
// outcomes go to the run-summary variables and dgc.log.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge_sync.groovy}}
// {{include:classification_sync.groovy}}

def ids = dxrModelIds()
if (execution.getVariable('syncFailed') == true) { return }

def classifications = buildEdgeSyncCatalogue(readJsonVariable('classIndex', [:]))
loggerApi.info("DataXray catalogue assembled: ${classifications.size()} classification(s); syncing into Collibra")

boolean annotatorsComplete = edgeSyncAnnotatorsComplete()
def res = syncClassificationCatalog(classifications, (execution.getVariable('dataxrayUrl') ?: '').toString(),
                                    [indexIdAttrTypeId: ids.dataxrayIndexIdAttrTypeId,
                                     noRetireTypeIds: annotatorsComplete ? [] : [ids.annotatorTypeId]])
if (!annotatorsComplete) {
    res.failures << "Note: DataXray's full annotator list exceeds Collibra's External API response limit, so only annotators with findings were synced and no annotator was retired this run"
}
execution.setVariable('syncCreatedCount', res.created)
execution.setVariable('syncUpdatedCount', res.updated)
execution.setVariable('syncRetiredCount', res.retired)
execution.setVariable('syncSkippedCount', res.skipped)
execution.setVariable('syncFailedCount',  res.failed)
execution.setVariable('syncFailures',     res.failures.join('; '))

if (res.created == 0 && res.updated == 0 && res.failed > 0) {
    loggerApi.error("DataXray sync run failed: all ${res.failed} classification(s) failed to sync. First error: ${res.failures[0]}")
}
