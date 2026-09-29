// sync_page.groovy  (interactive; both editions)
//
// ASYNC task after every transport task of the catalogue loop: folds the
// response into the catalogue state and arms the next request
// (shared/dxr_catalog_sync.groovy). Never throws — a failure is recorded in
// syncFailed/dxrErrorMessage and the loop ends.

// {{include:dxr_model.groovy}}
// {{include:dxr_catalog_sync.groovy}}

handleCatalogSyncPage(readJsonVariable('classIndex', [:])) { String msg ->
    loggerApi.error("Data X-Ray sync failed: ${msg}")
    execution.setVariable('syncFailed', true)
    execution.setVariable('dxrErrorMessage', msg)
    execution.setVariable('syncFailuresDisplay', msg)
    execution.setVariable('hasMoreWork', false)
}
