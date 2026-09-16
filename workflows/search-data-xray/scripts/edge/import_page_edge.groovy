// import_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after each results-page External API task of the import: parse the
// page, upsert it as one batch (shared/file_batch.groovy), arm the next page or
// finish. A page that fails three times aborts the import cleanly; the summary
// form then shows the counts reached so far plus the failure.
//
// Reads:  dxr* response/loop variables, classIndex, queryAssetId, dataxrayUrl, dataxrayPageSize
// Writes: import*Count, hasMoreWork, dxrFetchComplete, importAborted, importAbortReason

import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:file_batch.groovy}}

def classIndex = new JsonSlurper().parseText((execution.getVariable('classIndex') ?: '{"byDxrId":{},"byName":{}}').toString())

handleEdgeFilesPage([
    label       : 'Import batch',
    queryAssetId: string2Uuid((execution.getVariable('queryAssetId') ?: '').toString()),
    dataxrayUrl : (execution.getVariable('dataxrayUrl') ?: '').toString(),
    pageSize    : edgePageSize(execution.getVariable('dataxrayPageSize'), 50),
    classIndex  : classIndex,
    onPageZero  : { Map page ->
        // The preview may be a little stale; the cap was checked against it in
        // the collector, so only a hard paging-window violation is refused here.
        page.total > page.maxResultWindow
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
])
