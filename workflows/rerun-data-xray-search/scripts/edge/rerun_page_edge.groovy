// rerun_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after every External API task of the rerun: catalogue stages →
// compose the query_items → one results page per execution, upserted as one
// batch (shared/file_batch.groovy via dxr_edge.groovy's handleEdgeFilesPage).
// Never throws: failures set rerunAborted/rerunAbortReason and end the loop,
// which also keeps headless nightly runs from wedging.
//
// Per page it records the deterministic file-asset ids seen (rerunSeenChunk_<n>)
// so the retire pass can diff the complete current set against previousFileIds.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:file_batch.groovy}}

if (execution.getVariable('rerunAborted') == true) {
    execution.setVariable('hasMoreWork', false)
    return
}

def conditionName = (execution.getVariable('conditionName') ?: '').toString()

def abortRun = { String msg ->
    loggerApi.error("Rerun of '${conditionName}' aborted: ${msg}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', msg)
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('dxrFetchComplete', false)
}

def stage = (execution.getVariable('dxrStage') ?: '').toString()

if (stage == 'catalogue') {
    def r = readEdgeResponse()
    if (!r.ok) {
        if (edgeRetry("Data X-Ray ${stage} request", r.error)) { return }
        abortRun("${r.error}. Check the Edge HTTP connection '${execution.getVariable('dataxrayConnectionName')}'.".toString())
        return
    }
    edgeResetRetries()
    try {
        if (!advanceEdgeCatalogue(r.body)) { return }
    } catch (Exception catEx) {
        abortRun("Could not read the Data X-Ray catalogue: ${catEx.message}".toString())
        return
    }
    def criteria = new JsonSlurper().parseText(execution.getVariable('rerunCriteria').toString())
    def q = composeEdgeQueryItems(criteria, loadEdgeCatalogue())
    if (!q.unresolved.isEmpty()) {
        abortRun("Cannot rerun '${conditionName}': ${q.unresolved.join(', ')} ${q.unresolved.size() == 1 ? 'is' : 'are'} not visible to Data X-Ray through this connection (the Collibra asset's Data X-Ray ID is missing or stale, or the connection's Data X-Ray user cannot see that classification). Run Sync Data X-Ray Classifications with the same connection and rerun.".toString())
        return
    }
    execution.setVariable('dxrQueryItems', JsonOutput.toJson(q.items))
    startEdgeFilesPage(q.items, 0, edgePageSize(execution.getVariable('dataxrayPageSize'), 50), null)
    return
}

// stage == 'files'
def classIndex = new JsonSlurper().parseText((execution.getVariable('classIndex') ?: '{"byDxrId":{},"byName":{}}').toString())
def previousFileIds = new JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List
int filesDomainCount = (execution.getVariable('filesDomainCount') ?: 0) as int

handleEdgeFilesPage([
    label       : 'Import batch',
    queryAssetId: string2Uuid((execution.getVariable('queryAssetId') ?: '').toString()),
    dataxrayUrl : (execution.getVariable('dataxrayUrl') ?: '').toString(),
    pageSize    : edgePageSize(execution.getVariable('dataxrayPageSize'), 50),
    classIndex  : classIndex,
    onPageZero  : { Map page ->
        if (page.total > page.maxResultWindow) {
            return "Rerun of '${conditionName}' refused: it matches ${page.total} file(s), more than the ${page.maxResultWindow} results Data X-Ray can page through in one query.".toString()
        }
        // Files this query already returns update in place — only genuinely new
        // files grow the domain. Without the full row set up front, bound the
        // growth optimistically here; processFileBatch never creates beyond
        // what each page actually holds.
        int projected = filesDomainCount + Math.max(0, page.total - previousFileIds.size())
        if (projected > dxrMaxTotalFileAssets()) {
            return "Rerun of '${conditionName}' refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this rerun could add up to ${Math.max(0, page.total - previousFileIds.size())} more — the projected ${projected} exceeds the ${dxrMaxTotalFileAssets()} instance-wide limit.".toString()
        }
        return null
    },
    afterPage   : { List tuples, int pageNo ->
        def seen = tuples.collect { deterministicFileAssetId(it[0]).toString() }
        execution.setVariable("rerunSeenChunk_${pageNo}".toString(), JsonOutput.toJson(seen))
        execution.setVariable('rerunPageCount', pageNo + 1)
    },
    onFatal     : abortRun,
])
