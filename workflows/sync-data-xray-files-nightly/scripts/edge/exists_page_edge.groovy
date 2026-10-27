// exists_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after each existence-check External API task. Drives the rerun's
// page loop (handleEdgeFilesPage) over the current batch: each page of rows
// refreshes the file assets DataXray still has (shared/file_exists_edge.groovy).
// When the batch has been read to the end, the assets DataXray did not return
// are set to 'Not in DataXray' and the next batch is armed, or the loop ends.
// Never throws. A failed request is retried twice; after that the check stops
// for tonight — batches already applied stay applied, the rest are checked
// tomorrow.

// {{include:file_exists_edge.groovy}}

def ids = dxrModelIds()
int idx = (execution.getVariable('existsChunk') ?: 0) as int
int count = (execution.getVariable('existsChunkCount') ?: 0) as int
def what = "DataXray existence check ${idx + 1}/${count}".toString()
try {
    def chunks = unpackJson((execution.getVariable('existsWork') ?: '').toString()) as List
    def chunk = chunks[idx] as Map
    def classIndex = readJsonVariable('classIndex', [byDxrId: [:], byName: [:]])
    def opts = [
        label      : what,
        dataxrayUrl: '',
        pageSize   : dxrEdgeDefaultPageSize(),
        index      : classIndex,
        onPage     : { Map page, List tuples -> refreshExistsPage(chunk, tuples, classIndex, ids) },
        onSkip     : { String fileId -> onExistsRowSkipped(chunk, fileId) },
        onFatal    : { String msg ->
            loggerApi.error("DataXray existence check stopped at batch ${idx + 1}/${count} (${msg}); the remaining file assets are checked on the next run")
            execution.setVariable('hasMoreWork', false)
            execution.setVariable('dxrFetchComplete', false)
        },
    ]
    handleEdgeFilesPage(opts)
    if (execution.getVariable('dxrFetchComplete') == true) {
        finishExistsCheckChunk(chunk, ids)
        if (idx + 1 < count) {
            execution.setVariable('existsChunk', idx + 1)
            startExistsCheckChunk(chunks[idx + 1] as Map)
        } else {
            execution.setVariable('hasMoreWork', false)
            loggerApi.info("DataXray existence check done: ${execution.getVariable('nightlyRefreshed')} file asset(s) refreshed (+${execution.getVariable('nightlyLinksAdded')}/-${execution.getVariable('nightlyLinksRemoved')} classification link(s)), ${execution.getVariable('nightlyNotInDataXray')} set to 'Not in DataXray', ${execution.getVariable('nightlyBackInDataXray')} back to 'Not in any search'")
        }
    }
} catch (Exception pageEx) {
    loggerApi.error("${what} failed: ${pageEx.message} [${dxrVersionLabel()}]")
    execution.setVariable('hasMoreWork', false)
}
