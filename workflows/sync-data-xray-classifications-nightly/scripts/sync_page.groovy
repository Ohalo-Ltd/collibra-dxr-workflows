// sync_page.groovy  (nightly; both editions)
//
// ASYNC task after every transport task of the catalogue loop: folds the
// response into the catalogue state and arms the next request
// (shared/dxr_catalog_sync.groovy). Timer-triggered and unattended, so it never
// throws — a failure records an empty run summary and ends the loop.

// {{include:dxr_model.groovy}}
// {{include:dxr_catalog_sync.groovy}}

handleCatalogSyncPage(readJsonVariable('classIndex', [:])) { String msg ->
    loggerApi.error("Failed to fetch the classification catalogue from Data X-Ray: ${msg}")
    execution.setVariable('syncFailed', true)
    execution.setVariable('hasMoreWork', false)
    ['syncCreatedCount', 'syncUpdatedCount', 'syncRetiredCount', 'syncSkippedCount', 'syncFailedCount'].each { execution.setVariable(it, 0) }
    execution.setVariable('syncFailures', "fetch failed: ${msg}".toString())
}
