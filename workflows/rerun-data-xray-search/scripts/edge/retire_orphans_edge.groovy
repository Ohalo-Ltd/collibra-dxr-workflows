// retire_orphans_edge.groovy  (Collibra Cloud + Edge variant)
//
// After the results loop: refresh the query's Files preview, unlink files the
// query no longer returns and retire the ones no query returns any more
// (shared/file_batch.groovy: retireOrphanFiles), then hand the DataXray
// existence check (next task, shared/rerun_exists_edge.groovy) the files to
// ask DataXray about:
//   – the files this run parked (rerunExistsMode 'orphans'): a file DataXray
//     no longer has becomes 'Not in DataXray'; one it still has gets its
//     attributes and classification links refreshed — a label removed in
//     DataXray becomes 'formerly groups' — as the rerun ends, not the next
//     night (TRC stories #28/#29/#31);
//   – after a 0-results answer (rerunExistsMode 'zeroResults'): every file
//     the search returned before, so a search whose whole datasource was
//     deleted in DataXray is settled (unlinked, 'Not in DataXray') once
//     DataXray confirms it has none of them, and left alone otherwise.
//
// The preview is written once every page was fetched — 0 results included,
// which the retire pass itself distrusts but the preview reports as it is.
//
// Safety rails (in addition to the on-prem ones — aborted run, zero results):
//   – The pass only runs when EVERY results page was fetched (dxrFetchComplete).
//     A run that died mid-walk must not treat the partial set as mass
//     disappearance; it is retried next night / next rerun.
//   – Nor when a result had to be skipped without its file id being readable
//     (rerunRetireUnsafe): that file may be one previously returned.
//
// Reads:  rerunAborted, dxrFetchComplete, importTotal, rerunSeenChunk_<n>, rerunPageCount,
//         previousFileIds, queryAssetId, rerunPreviewRows
// Writes: rerunRetiredCount, rerunUnlinkedCount, rerunRetireSkipped/rerunRetireSkipReason,
//         rerunExistsMode, rerunExistsCandidates

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:file_batch.groovy}}
// {{include:query_preview.groovy}}
// {{include:rerun_common.groovy}}

execution.setVariable('rerunExistsMode', '')
execution.setVariable('rerunExistsCandidates', '[]')

if (execution.getVariable('rerunAborted') == true) {
    loggerApi.info('Retire pass skipped: the rerun was aborted')
    return
}
if (execution.getVariable('dxrFetchComplete') != true) {
    loggerApi.warn('Retire pass skipped: not every results page was fetched — not retiring anything from a partial picture')
    noteRetireSkipped('Not every page of results could be read from DataXray, so no file was unlinked from this search this time. The next rerun tries again.')
    return
}

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
int total = (execution.getVariable('importTotal') ?: 0) as int
refreshQueryPreview(queryAssetId, dxrModelIds().filesAttrTypeId,
    new JsonSlurper().parseText((execution.getVariable('rerunPreviewRows') ?: '[]').toString()) as List, total)

if (execution.getVariable('rerunRetireUnsafe') == true) {
    loggerApi.warn('Retire pass skipped: a result too large to fetch was skipped and its file id could not be read — not retiring anything this run')
    noteRetireSkipped('One result was too large to send through Collibra Edge and could not be identified, so no file was unlinked from this search this time (it might have been one of them). The file is named in the Collibra log.')
    return
}

def previousIds = new JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List

if (total == 0) {
    loggerApi.warn('Retire pass skipped: the rerun returned 0 results — not retiring anything on that answer alone; asking DataXray about each previously returned file instead')
    // Provisional: the existence check's verdict replaces it (rerun_exists_edge.groovy).
    noteRetireSkipped(zeroResultsRetireSkipReason(previousIds.size()))
    if (!previousIds.isEmpty()) {
        execution.setVariable('rerunExistsMode', 'zeroResults')
        execution.setVariable('rerunExistsCandidates', JsonOutput.toJson(previousIds))
    }
    return
}

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

def r = retireOrphanFiles(currentIds, previousIds, queryAssetId)

chunkNames.each { name ->
    try { execution.removeVariable(name) } catch (Exception ignored) { /* best-effort */ }
}
execution.setVariable('rerunRetiredCount', r.retired)
execution.setVariable('rerunUnlinkedCount', r.unlinked)
if (!r.parkedIds.isEmpty()) {
    execution.setVariable('rerunExistsMode', 'orphans')
    execution.setVariable('rerunExistsCandidates', JsonOutput.toJson(r.parkedIds))
}
loggerApi.info("Retire pass complete: ${r.unlinked} file(s) unlinked from the query, ${r.retired} retired (no longer returned by any query); ${r.parkedIds.size()} to check with DataXray")
