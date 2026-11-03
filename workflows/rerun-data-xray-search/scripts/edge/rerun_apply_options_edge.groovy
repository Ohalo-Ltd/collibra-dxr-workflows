// rerun_apply_options_edge.groovy  (Collibra Cloud + Edge variant)
//
// SYNCHRONOUS task right after the Rerun Options task (or straight after the
// collector in a headless run): applies the options (applyRerunOptions,
// rerun_common.groovy — the sync filter's tags, and whether this run creates
// assets for new files) and arms page 0 of the results. The per-run and
// instance-wide limits are checked on the first page that arrives
// (rerun_page_edge.groovy, onPageZero), as before v2.10.
//
// It must always arm a request: it feeds the External API task directly, and
// one with nothing armed fails on the unset ${dxrMethod} (the 29 Oct 2026
// nightly bug). The only exit without arming is the WorkflowException
// applyRerunOptions throws for a blocked request on the interactive task,
// which rolls back and leaves the task open.
//
// Writes: rerunCreateNew, the sync filter's tags, the first page request

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:rerun_common.groovy}}

def queryId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
boolean createNew = applyRerunOptions(queryId)

int pageSize = edgePageSize(execution.getVariable('dataxrayPageSize'))
beginEdgeFilesPass()
startEdgeFilesPage(readJsonVariable('dxrQueryItems', []), 0, pageSize, null)
loggerApi.info("Rerun of '${execution.getVariable('conditionName')}': ${createNew ? 'creating assets for new files' : 'refreshing the files already in Collibra only'}; page 0 armed")
