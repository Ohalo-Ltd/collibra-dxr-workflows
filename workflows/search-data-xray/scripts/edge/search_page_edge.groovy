// search_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task that runs after every External API task of the search phase.
// Two passes through the results, one page per execution (plus any
// datasource-name lookups a page needs):
//
//   1. preview       — the first page, shown on the query asset and the results
//                      task. Never throws: a failure is recorded in
//                      searchFailed/dxrErrorMessage and the loop ends, so the
//                      user gets a "Search Failed" task instead of a stuck job.
//   2. results file  — every matching file's datasource and path, 100 slim rows
//                      per request, kept as CSV text chunks that the finalize
//                      script zips and attaches (the on-prem edition's ZIP'd
//                      CSV). Skipped above dxrMaxImportFiles() (the user is asked
//                      to refine the search) and when the preview already holds
//                      every row. A failure here does not fail the search: the
//                      results task says the file could not be built.
//
// Reads:  dxr* response/loop variables, dataxrayUrl, dataxrayPageSize, dxrSearchPhase
// Writes: searchPreviewRows (JSON tuples), resultCount, dxrMaxResultWindow,
//         dxrPreviewPageSize, dxrPreviewPitId, dxrDatasourceNames, searchFailed, dxrErrorMessage,
//         resultsFileStatus ('none' | 'complete' | 'tooMany' | 'failed'),
//         resultsFileError, resultsFileSkipped, resultsFileChunks,
//         resultsFileChunk_<n>, hasMoreWork

import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:search_common.groovy}}
// {{include:dxr_edge.groovy}}

def dataxrayUrl = (execution.getVariable('dataxrayUrl') ?: '').toString()
def emptyIndex = [labelDxrIdByIndexId: [:], annotatorDxrIdByIndexId: [:], extractorDxrIdByIndexId: [:], nameByDxrId: [:]]

// One chunk of the results file: CSV lines (no header) for these tuples.
def writeResultsFileChunk = { List tuples ->
    int n = (execution.getVariable('resultsFileChunks') ?: 0) as int
    def sb = new StringBuilder()
    tuples.each { t -> sb.append(csvEscape(t[1])).append(',').append(csvEscape(t[2])).append('\r\n') }
    execution.setVariable("resultsFileChunk_${n}".toString(), sb.toString())
    execution.setVariable('resultsFileChunks', n + 1)
}

// After the preview: decide whether the results file needs a pass of its own.
def startResultsFile = {
    int total = (execution.getVariable('resultCount') ?: 0) as int
    int window = (execution.getVariable('dxrMaxResultWindow') ?: 10_000) as int
    int limit = Math.min(dxrMaxImportFiles(), window)
    execution.setVariable('resultsFileChunks', 0)
    execution.setVariable('resultsFileSkipped', 0)
    execution.setVariable('resultsFileError', '')
    execution.setVariable('dxrSearchPhase', 'done')
    if (total == 0) {
        execution.setVariable('resultsFileStatus', 'none')
        return
    }
    if (total > limit) {
        execution.setVariable('resultsFileStatus', 'tooMany')
        loggerApi.info("No results file: the search matched ${total} file(s), more than ${limit}")
        return
    }
    def previewTuples = readJsonVariable('searchPreviewRows', [])
    if (previewTuples.size() >= total) {
        writeResultsFileChunk(previewTuples)
        execution.setVariable('resultsFileStatus', 'complete')
        return
    }
    execution.setVariable('dxrSearchPhase', 'file')
    execution.setVariable('resultsFileStatus', 'building')
    beginEdgeFilesPass(edgeResultsFileExcludedFields())
    // Same point-in-time snapshot as the preview, so the file matches its total.
    startEdgeFilesPage(readJsonVariable('dxrQueryItems', []), 0, dxrEdgeMaxPageSize(),
        (execution.getVariable('dxrPreviewPitId') ?: '').toString())
    loggerApi.info("Building the results file: ${total} file(s), ${dxrEdgeMaxPageSize()} per request")
}

def phase = (execution.getVariable('dxrSearchPhase') ?: 'preview').toString()

if (phase == 'file') {
    handleEdgeFilesPage([
        label      : 'Results file',
        dataxrayUrl: dataxrayUrl,
        pageSize   : dxrEdgeMaxPageSize(),
        index      : emptyIndex,
        onFatal    : { String msg ->
            loggerApi.warn("Results file not attached: ${msg}")
            execution.setVariable('resultsFileStatus', 'failed')
            execution.setVariable('resultsFileError', msg)
            execution.setVariable('hasMoreWork', false)
        },
        onSkip     : { String fileId ->
            execution.setVariable('resultsFileSkipped', ((execution.getVariable('resultsFileSkipped') ?: 0) as int) + 1)
        },
        onPage     : { Map page, List tuples ->
            if (!tuples.isEmpty()) { writeResultsFileChunk(tuples) }
            return true
        },
    ])
    if (execution.getVariable('dxrFetchComplete') == true && execution.getVariable('resultsFileStatus') == 'building') {
        execution.setVariable('resultsFileStatus', 'complete')
        execution.setVariable('dxrSearchPhase', 'done')
    }
    return
}

// phase == 'preview'
int configuredPageSize = edgePageSize(execution.getVariable('dataxrayPageSize'))
handleEdgeFilesPage([
    label      : 'Search preview',
    dataxrayUrl: dataxrayUrl,
    pageSize   : configuredPageSize,
    index      : emptyIndex,
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
        execution.setVariable('dxrPreviewPitId', page.pitId ?: '')
        loggerApi.info("Data X-Ray returned ${page.total} file(s); preview holds ${tuples.size()} (max_result_window ${page.maxResultWindow})")
        return false   // preview only: one page
    },
])
if (execution.getVariable('searchFailed') != true && execution.getVariable('dxrFetchComplete') == true) {
    execution.setVariable('dxrPreviewPageSize', edgeCurrentPageSize(configuredPageSize))
    startResultsFile()
}
