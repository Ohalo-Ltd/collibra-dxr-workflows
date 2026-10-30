// rerun_exists_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after each existence-check External API task of the rerun: one
// page of the current batch (shared/file_exists_edge.groovy); when the check
// ends — every batch read, or a failure — the verdict is applied and the
// summary variables written (shared/rerun_exists_edge.groovy). Never throws.

// {{include:rerun_exists_edge.groovy}}

def ids = dxrModelIds()
handleExistsCheckPage(ids, rerunExistsLabel())
if (execution.getVariable('hasMoreWork') != true) {
    concludeRerunExistsCheck(ids)
}
