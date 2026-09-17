// search_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task that runs after every External API task of the search phase:
// the preview page (plus any datasource-name lookups it needs). Never throws:
// a failure is recorded in searchFailed/dxrErrorMessage and the loop ends, so
// the user gets a "Search Failed" task instead of a stuck job.
//
// Reads:  dxr* response/loop variables, dataxrayUrl
// Writes: searchPreviewRows (JSON tuples), resultCount, dxrMaxResultWindow,
//         dxrDatasourceNames, searchFailed, dxrErrorMessage, hasMoreWork

import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}

handleEdgeFilesPage([
    label      : 'Search preview',
    dataxrayUrl: (execution.getVariable('dataxrayUrl') ?: '').toString(),
    pageSize   : 50,
    index      : [labelDxrIdByIndexId: [:], annotatorDxrIdByIndexId: [:], extractorDxrIdByIndexId: [:], nameByDxrId: [:]],
    onFatal    : { String msg ->
        loggerApi.error("Search Data X-Ray failed: ${msg}")
        execution.setVariable('searchFailed', true)
        execution.setVariable('dxrErrorMessage', msg)
        execution.setVariable('hasMoreWork', false)
    },
    onPage     : { Map page, List tuples ->
        execution.setVariable('searchPreviewRows', JsonOutput.toJson(tuples))
        execution.setVariable('resultCount', page.total)
        execution.setVariable('dxrMaxResultWindow', page.maxResultWindow)
        loggerApi.info("Data X-Ray returned ${page.total} file(s); preview holds ${tuples.size()} (max_result_window ${page.maxResultWindow})")
        return false   // preview only: one page
    },
])
