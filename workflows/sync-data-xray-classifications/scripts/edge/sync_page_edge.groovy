// sync_page_edge.groovy  (interactive; Collibra Cloud + Edge variant)
//
// ASYNC task after every External API task of the catalogue loop: folds the
// response into the catalogue state and arms the next request
// (shared/dxr_edge_sync.groovy). Never throws — a failure is recorded in
// syncFailed/dxrErrorMessage and the loop ends.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge_sync.groovy}}

handleEdgeSyncPage(readJsonVariable('classIndex', [:])) { String msg ->
    loggerApi.error("Data X-Ray sync failed: ${msg}")
    execution.setVariable('syncFailed', true)
    execution.setVariable('dxrErrorMessage', msg)
    execution.setVariable('syncFailuresDisplay', msg)
    execution.setVariable('hasMoreWork', false)
}
