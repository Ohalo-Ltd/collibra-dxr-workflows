// exists_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after each existence-check External API task: applies the answer
// to its batch (shared/file_exists_edge.groovy), then arms the next batch or
// ends the loop. Never throws. A failed request is retried twice; after that
// the check stops for tonight — batches already applied stay applied, the rest
// are checked tomorrow.

// {{include:file_exists_edge.groovy}}

def ids = dxrModelIds()
int idx = (execution.getVariable('existsChunk') ?: 0) as int
int count = (execution.getVariable('existsChunkCount') ?: 0) as int
def what = "DataXray existence check request ${idx + 1}/${count}"
execution.setVariable('hasMoreWork', false)
try {
    def chunks = unpackJson((execution.getVariable('existsWork') ?: '').toString()) as List
    def r = readEdgeResponse()
    if (!r.ok) {
        if (edgeRetry(what, r.error)) { return }
        loggerApi.error("DataXray existence check stopped at request ${idx + 1}/${count}; the remaining file assets are checked on the next run")
        return
    }
    edgeResetRetries()
    def res = applyExistsCheckPage(chunks[idx] as Map, parseEdgeSearchPage(r.body), ids)
    execution.setVariable('nightlyNotInDataXray', ((execution.getVariable('nightlyNotInDataXray') ?: 0) as int) + res.gone)
    execution.setVariable('nightlyBackInDataXray', ((execution.getVariable('nightlyBackInDataXray') ?: 0) as int) + res.back)
    if (idx + 1 < count) {
        execution.setVariable('existsChunk', idx + 1)
        armExistsCheckRequest(chunks[idx + 1] as Map)
        execution.setVariable('hasMoreWork', true)
    } else {
        loggerApi.info("DataXray existence check done: ${execution.getVariable('nightlyNotInDataXray')} file asset(s) set to 'Not in DataXray', ${execution.getVariable('nightlyBackInDataXray')} back to 'Not in any search'")
    }
} catch (Exception pageEx) {
    loggerApi.error("${what} failed: ${pageEx.message} [${dxrVersionLabel()}]")
}
