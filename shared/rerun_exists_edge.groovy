// rerun_exists_edge.groovy — the DataXray existence check at the end of a
// Cloud + Edge rerun, and what the rerun summary says about it.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// The retire pass (retire_orphans_edge.groovy) decides what to check:
//   rerunExistsMode        'orphans'     – the files this run stopped returning
//                                          and parked (Not in any search / Not in DataXray)
//                          'zeroResults' – the rerun got 0 results: every file
//                                          the search returned before
//                          ''            – nothing (aborted, partial, unsafe)
//   rerunExistsCandidates  JSON list of those file-asset UUIDs
// rerun_exists_prepare_edge.groovy arms the check (prepareExistsCheck),
// rerun_exists_page_edge.groovy walks it (handleExistsCheckPage), and
// concludeRerunExistsCheck, run when it ends, applies the zero-results verdict
// and writes the summary form's variables:
//   rerunExistsRan    (Boolean) – the check ran (the summary shows its counters)
//   rerunExistsNoted  (Boolean) / rerunExistsNote (String) – an outcome worth a sentence
//   rerunRetireSkipped / rerunRetireSkipReason, rerunUnlinkedCount – updated by the verdict

// {{include:file_exists_edge.groovy}}
// {{include:rerun_common.groovy}}

def rerunExistsLabel() {
    return "Rerun of '${execution.getVariable('conditionName') ?: ''}': DataXray existence check".toString()
}

// Start the check the retire pass asked for. Returns true when a request was
// armed (hasMoreWork); otherwise the conclusion is written right away.
def startRerunExistsCheck(Map ids) {
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('rerunExistsRan', false)
    def mode = (execution.getVariable('rerunExistsMode') ?: '').toString()
    def candidates = readJsonVariable('rerunExistsCandidates', [])
    if (execution.getVariable('rerunAborted') == true || mode.isEmpty() || candidates.isEmpty()) {
        return false
    }
    execution.setVariable('rerunExistsRan', true)
    Closure eligible = null
    if (mode == 'orphans') {
        eligible = { sid -> sid == ids.notInAnySearchStatusId || sid == ids.notInDataXrayStatusId }
    }
    boolean armed = prepareExistsCheck(ids, [mode: mode, assetIds: candidates, eligible: eligible, label: rerunExistsLabel()])
    if (!armed) { concludeRerunExistsCheck(ids) }
    return armed
}

def noteRerunExists(String note) {
    execution.setVariable('rerunExistsNoted', !note.isEmpty())
    execution.setVariable('rerunExistsNote', note)
}

// The check has ended (every chunk read, nothing to check, or stopped by a
// failure): apply the zero-results verdict and write the summary variables.
def concludeRerunExistsCheck(Map ids) {
    def mode = (execution.getVariable('existsMode') ?: '').toString()
    int candidates = (execution.getVariable('existsCandidates') ?: 0) as int
    boolean complete = execution.getVariable('existsComplete') == true
    if (mode == 'zeroResults') {
        def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
        def r = concludeZeroResultsCheck(ids, queryAssetId)
        int linked = previousFileCount()
        def closing = 'If DataXray really has none of these files any more, delete this sync filter: the nightly file sync then marks the files no other search returns as Not in any search.'
        if (r.allGone) {
            execution.setVariable('rerunRetireSkipped', false)
            execution.setVariable('rerunRetireSkipReason', '')
            execution.setVariable('rerunUnlinkedCount', r.unlinked)
            noteRerunExists("DataXray returned 0 results for this search, so it was asked about each of the ${linked} file(s) the search returned before, by DataXray Datasource ID and Object ID. It has none of them any more — the files, or their datasource, were deleted in DataXray — so all ${r.unlinked} were unlinked from this search (now <i>formerly returns</i>) and ${r.gone} set to <i>Not in DataXray</i> (a status set by a person, or a file another search still returns, keeps its status).".toString())
            loggerApi.info("${rerunExistsLabel()}: 0 results and none of the ${linked} previously returned file(s) exist in DataXray — ${r.unlinked} unlinked, ${r.gone} set to 'Not in DataXray'")
        } else if (r.found > 0) {
            noteRetireSkipped("DataXray returned 0 results for this search but still has ${r.found} of the ${linked} file(s) it returned before, so all ${linked} were left linked to it and unchanged (the ${r.found} were refreshed). An empty answer while the files are still there is far more often a DataXray index or connection problem than a search that really matches nothing, so a rerun never acts on one. If this search really matches nothing any more, delete this sync filter: the nightly file sync then marks the files no other search returns as Not in any search.".toString())
            noteRerunExists('')
        } else {
            def why = !complete ? 'the check could not be completed'
                : (r.unsafe ? 'one result could not be read'
                : (r.noKey > 0 ? "${r.noKey} of them have no DataXray Datasource ID / Object ID yet" : 'none of them could be checked'))
            noteRetireSkipped("DataXray returned 0 results for this search, so its ${linked} file(s) were left linked to it and unchanged. Whether DataXray still has them could not be established (${why}); the nightly file sync checks again. ${closing}".toString())
            noteRerunExists('')
        }
        return
    }
    if (mode == 'orphans' && !complete) {
        noteRerunExists("Whether DataXray still has the ${candidates} file(s) this search no longer returns could not be fully checked; the nightly file sync checks them again.".toString())
        return
    }
    noteRerunExists('')
}
