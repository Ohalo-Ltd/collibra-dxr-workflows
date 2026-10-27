// exists_prepare_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs right after the nightly driver (fan-out + orphan sweep): lists the file
// assets no search returns ('Not in any search', 'Not in DataXray'), groups
// them by datasource into batches and arms the first page of the first batch's
// existence check (see shared/file_exists_edge.groovy). Synchronous and
// timer-started, so it NEVER throws: any failure logs and ends the run
// (hasMoreWork false).
//
// Process variables produced (audit only):
//   nightlyExistsCandidates (Integer) – file assets to check
//   nightlyExistsNoKey      (Integer) – of those, skipped: no object key stored
//   nightlyNotInDataXray    (Integer) – set to 'Not in DataXray' (page task)
//   nightlyBackInDataXray   (Integer) – back to 'Not in any search' (page task)
//   nightlyRefreshed        (Integer) – found in DataXray and refreshed (page task)
//   nightlyLinksAdded       (Integer) – classification links added (page task)
//   nightlyLinksRemoved     (Integer) – links swapped to 'formerly groups' (page task)
//   nightlyRefreshSkipped   (Integer) – rows too large to fetch, left as they are

// {{include:file_exists_edge.groovy}}

def ids = dxrModelIds()
execution.setVariable('hasMoreWork', false)
['nightlyNotInDataXray', 'nightlyBackInDataXray', 'nightlyRefreshed', 'nightlyLinksAdded',
 'nightlyLinksRemoved', 'nightlyRefreshSkipped'].each { execution.setVariable(it, 0) }
try {
    if (!statusApi.exists(ids.notInDataXrayStatusId)) {
        loggerApi.warn("DataXray existence check skipped: the status 'Not in DataXray' does not exist. Run Configure DataXray Workflows once after upgrading.")
        return
    }
    def work = buildExistsCheckWork(ids)
    execution.setVariable('nightlyExistsCandidates', work.candidates)
    execution.setVariable('nightlyExistsNoKey', work.noKey)
    if (work.noKey > 0) {
        loggerApi.info("DataXray existence check: ${work.noKey} file asset(s) have no DataXray Datasource ID / Object ID yet and are not checked (a rerun of a search that returns them stores both)")
    }
    if (work.chunks.isEmpty()) {
        loggerApi.info("DataXray existence check: nothing to check (${work.candidates} file asset(s) in no search)")
        return
    }
    loggerApi.info("DataXray existence check: ${work.candidates - work.noKey} file asset(s) in ${work.chunks.size()} batch(es)")
    execution.setVariable('classIndex', groovy.json.JsonOutput.toJson(buildEdgeClassificationIndex(ids)))
    execution.setVariable('dxrDatasourceNames', '{}')
    execution.setVariable('existsWork', packJson(work.chunks))
    execution.setVariable('existsChunk', 0)
    execution.setVariable('existsChunkCount', work.chunks.size())
    startExistsCheckChunk(work.chunks[0] as Map)
} catch (Exception prepEx) {
    loggerApi.error("DataXray existence check skipped: ${prepEx.message}")
    execution.setVariable('hasMoreWork', false)
}
