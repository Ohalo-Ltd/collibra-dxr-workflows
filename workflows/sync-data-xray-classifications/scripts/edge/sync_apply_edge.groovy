// sync_apply_edge.groovy  (interactive; Collibra Cloud + Edge variant)
//
// ASYNC task after the catalogue loop: assemble the catalogue from the fetched
// lists and details and sync it into Collibra (shared/classification_sync.groovy),
// stamping each asset's numeric Data X-Ray Index ID. Never throws — a failure is
// recorded in syncFailed/dxrErrorMessage and the "Sync Failed" form shows it.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge_sync.groovy}}
// {{include:classification_sync.groovy}}

def ids = dxrModelIds()
if (execution.getVariable('syncFailed') == true) { return }

def classifications = buildEdgeSyncCatalogue(readJsonVariable('classIndex', [:]))
loggerApi.info("Data X-Ray catalogue assembled: ${classifications.size()} classification(s); syncing into Collibra")

boolean annotatorsComplete = edgeSyncAnnotatorsComplete()
def res = syncClassificationCatalog(classifications, (execution.getVariable('dataxrayUrl') ?: '').toString(),
                                    [indexIdAttrTypeId: ids.dataxrayIndexIdAttrTypeId,
                                     noRetireTypeIds: annotatorsComplete ? [] : [ids.annotatorTypeId]])
if (!annotatorsComplete) {
    res.failures << "Note: Data X-Ray's full annotator list exceeds Collibra's External API response limit, so only annotators with findings were synced and no annotator was retired this run"
}

execution.setVariable('syncCreatedCount', res.created)
execution.setVariable('syncUpdatedCount', res.updated)
execution.setVariable('syncRetiredCount', res.retired)
execution.setVariable('syncSkippedCount', res.skipped)
execution.setVariable('syncFailedCount',  res.failed)
execution.setVariable('syncFailures',     res.failures.join('; '))
execution.setVariable('syncFailuresDisplay', res.failures.isEmpty() ? 'None' : res.failures.join('; '))

if (res.created == 0 && res.updated == 0 && res.failed > 0) {
    def msg = "All ${res.failed} classification(s) failed to sync. First error: ${res.failures[0]}".toString()
    loggerApi.error("Data X-Ray sync failed: ${msg}")
    execution.setVariable('syncFailed', true)
    execution.setVariable('dxrErrorMessage', msg)
}
