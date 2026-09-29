// import_page.groovy  (both editions)
//
// ASYNC task after each results-page transport task of the import: parse the
// page, upsert it as one batch (shared/file_batch.groovy), arm the next page or
// finish. Datasource names are looked up per id on first sight. A page that
// fails three times aborts the import cleanly; the summary form then shows the
// counts reached so far plus the failure. A single result too large for
// Collibra to receive even alone is skipped and counted as failed.
//
// Reads:  dxr* response/loop variables, classIndex, queryAssetId, dataxrayUrl, dataxrayPageSize
// Writes: import*Count, hasMoreWork, dxrFetchComplete, importAborted, importAbortReason

// {{include:dxr_model.groovy}}
// {{include:dxr_search.groovy}}
// {{include:file_batch.groovy}}

def classIndex = readJsonVariable('classIndex', [byDxrId: [:], byName: [:]])
def opts = [
    label       : 'Import batch',
    queryAssetId: string2Uuid((execution.getVariable('queryAssetId') ?: '').toString()),
    dataxrayUrl : (execution.getVariable('dataxrayUrl') ?: '').toString(),
    pageSize    : clampPageSize(execution.getVariable('dataxrayPageSize')),
    index       : classIndex,
    classIndex  : classIndex,
    onPageZero  : { Map page ->
        // The preview may be a little stale; the cap was checked against it in
        // the collector, so only a hard paging-window violation is refused here.
        page.total > dxrMaxImportFiles()
            ? "Import aborted: the search now matches ${page.total} file(s), more than the ${dxrMaxImportFiles()} a search can import. Refine the search and run it again.".toString()
            : page.total > page.maxResultWindow
            ? "Import aborted: the search now matches ${page.total} file(s), more than the ${page.maxResultWindow} results Data X-Ray can page through.".toString()
            : null
    },
    onFatal     : { String msg ->
        loggerApi.error("Import aborted: ${msg}")
        execution.setVariable('importAborted', true)
        execution.setVariable('importAbortReason', msg)
        int total = (execution.getVariable('importTotal') ?: 0) as int
        int done = ((execution.getVariable('importCreatedCount') ?: 0) as int) + ((execution.getVariable('importUpdatedCount') ?: 0) as int) + ((execution.getVariable('importFailedCount') ?: 0) as int)
        execution.setVariable('importFailedCount', ((execution.getVariable('importFailedCount') ?: 0) as int) + Math.max(0, total - done))
        execution.setVariable('hasMoreWork', false)
    },
]
opts.onSkip = { String assetId ->
    execution.setVariable('importFailedCount', ((execution.getVariable('importFailedCount') ?: 0) as int) + 1)
}
opts.onPage = { Map page, List tuples -> importFilesPage(opts, page, tuples) }
handleFilesPage(opts)
