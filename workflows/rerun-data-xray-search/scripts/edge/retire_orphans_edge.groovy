// retire_orphans_edge.groovy  (Collibra Cloud + Edge variant)
//
// Final stage of the rerun: unlink files the query no longer returns and retire
// the ones no query returns any more (shared/file_batch.groovy: retireOrphanFiles).
//
// Safety rails (in addition to the on-prem ones — aborted run, zero results):
//   – The pass only runs when EVERY results page was fetched (dxrFetchComplete).
//     A run that died mid-walk must not treat the partial set as mass
//     disappearance; it is retried next night / next rerun.
//
// Reads:  rerunAborted, dxrFetchComplete, importTotal, rerunSeenChunk_<n>, rerunPageCount,
//         previousFileIds, queryAssetId
// Writes: rerunRetiredCount, rerunUnlinkedCount

import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:file_batch.groovy}}

if (execution.getVariable('rerunAborted') == true) {
    loggerApi.info('Retire pass skipped: the rerun was aborted')
    return
}
if (execution.getVariable('dxrFetchComplete') != true) {
    loggerApi.warn('Retire pass skipped: not every results page was fetched — not retiring anything from a partial picture')
    return
}
int total = (execution.getVariable('importTotal') ?: 0) as int
if (total == 0) {
    loggerApi.warn('Retire pass skipped: the rerun returned 0 results — not retiring anything in case Data X-Ray answered incompletely')
    return
}

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
int pageCount = (execution.getVariable('rerunPageCount') ?: 0) as int

def currentIds = [] as Set
def chunkNames = []
for (int n = 0; n < pageCount; n++) {
    def name = "rerunSeenChunk_${n}".toString()
    def raw = execution.getVariable(name)
    if (raw != null) {
        currentIds.addAll(new JsonSlurper().parseText(raw.toString()) as List)
        chunkNames << name
    }
}
def previousIds = new JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List

def r = retireOrphanFiles(currentIds, previousIds, queryAssetId)

chunkNames.each { name ->
    try { execution.removeVariable(name) } catch (Exception ignored) { /* best-effort */ }
}
execution.setVariable('rerunRetiredCount', r.retired)
execution.setVariable('rerunUnlinkedCount', r.unlinked)
loggerApi.info("Retire pass complete: ${r.unlinked} file(s) unlinked from the query, ${r.retired} retired (no longer returned by any query)")
