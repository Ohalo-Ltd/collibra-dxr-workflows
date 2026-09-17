// rerun_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after every External API task of the rerun: one results page per
// execution (plus datasource-name lookups on first sight), upserted as one
// batch (shared/file_batch.groovy via dxr_edge.groovy). Never throws: failures
// set rerunAborted/rerunAbortReason and end the loop, which also keeps headless
// nightly runs from wedging.
//
// Per page it records the deterministic file-asset ids seen (rerunSeenChunk_<n>)
// so the retire pass can diff the complete current set against previousFileIds.

import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:file_batch.groovy}}

if (execution.getVariable('rerunAborted') == true) {
    execution.setVariable('hasMoreWork', false)
    return
}

def conditionName = (execution.getVariable('conditionName') ?: '').toString()
def classIndex = readJsonVariable('classIndex', [byDxrId: [:], byName: [:]])
def previousFileIds = readJsonVariable('previousFileIds', [])
int filesDomainCount = (execution.getVariable('filesDomainCount') ?: 0) as int

def opts = [
    label       : 'Import batch',
    queryAssetId: string2Uuid((execution.getVariable('queryAssetId') ?: '').toString()),
    dataxrayUrl : (execution.getVariable('dataxrayUrl') ?: '').toString(),
    pageSize    : edgePageSize(execution.getVariable('dataxrayPageSize')),
    index       : classIndex,
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
    onFatal     : { String msg ->
        loggerApi.error("Rerun of '${conditionName}' aborted: ${msg}")
        execution.setVariable('rerunAborted', true)
        execution.setVariable('rerunAbortReason', msg)
        execution.setVariable('hasMoreWork', false)
        execution.setVariable('dxrFetchComplete', false)
    },
]
opts.onPage = { Map page, List tuples -> importEdgePage(opts, page, tuples) }
handleEdgeFilesPage(opts)
