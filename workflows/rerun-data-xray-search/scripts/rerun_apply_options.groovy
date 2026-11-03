// rerun_apply_options.groovy  (on-prem variant)
//
// SYNCHRONOUS task right after the Rerun Options task (or straight after the
// collector in a headless run). The whole DataXray answer is already in the
// work list; this decides what the run does with it:
//   – interactive: writes the sync filter's tags from the options (keep in
//     sync nightly, create assets for new files on later reruns) and creates
//     assets for new files on this run only when "create assets for the new
//     matching files" was ticked (applyRerunOptions, rerun_common.groovy);
//   – headless (the nightly): follows the tags, as before v2.10;
//   – checks the per-run and instance-wide limits for the files it would
//     create (an interactive refusal surfaces on the task, which stays open;
//     a headless one ends the run cleanly with rerunAborted);
//   – refreshes the sync filter's "Files" preview from the answer.
//
// Reads:  rerunAborted, headless, queryAssetId, conditionName, importDecision,
//         keepInSync, autoImportNewFiles, importBlocked(+Message),
//         rerunTagNoAutoImport, rerunNewFileCount, rerunSkippedNoId,
//         filesDomainCount, importWorkList
// Writes: rerunCreateNew; on a headless refusal rerunAborted/rerunAbortReason
//         and an empty work list

import com.collibra.dgc.workflow.api.exception.WorkflowException

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:rerun_common.groovy}}
// {{include:query_preview.groovy}}
// {{include:worklist.groovy}}

if (execution.getVariable('rerunAborted') == true) { return }

def ids = dxrModelIds()
boolean headless = (execution.getVariable('headless') ?: '').toString() == 'true'
def queryId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
def conditionName = (execution.getVariable('conditionName') ?: '').toString()

boolean createNew = applyRerunOptions(queryId)

// --- Limits for the files this run would create ------------------------------------

int newFiles = createNew ? ((execution.getVariable('rerunNewFileCount') ?: 0) as int) : 0
int filesDomainCount = (execution.getVariable('filesDomainCount') ?: 0) as int
int projectedTotal = filesDomainCount + newFiles
def fmt = { int n -> String.format(Locale.ENGLISH, '%,d', n) }
String refusal = null
if (newFiles > dxrMaxImportFiles()) {
    refusal = "Rerun of '${conditionName}' refused: it would add ${fmt(newFiles)} new file asset(s), more than the ${fmt(dxrMaxImportFiles())} one run may create. Refine the search, or untick creating assets for new files to refresh only the files already imported.".toString()
} else if (newFiles > 0 && projectedTotal > dxrMaxTotalFileAssets()) {
    refusal = "Rerun of '${conditionName}' refused: Collibra holds ${fmt(filesDomainCount)} DataXray file asset(s) and this rerun would add ${fmt(newFiles)} more — the projected ${fmt(projectedTotal)} exceeds the ${fmt(dxrMaxTotalFileAssets())} instance-wide limit.".toString()
}
if (refusal != null) {
    loggerApi.error("Rerun DataXray Search aborted: ${refusal}")
    if (!headless) {
        def wf = new WorkflowException(refusal)
        wf.setTitleMessage('Rerun DataXray Search refused')
        wf.setUserMessage(refusal)
        throw wf
    }
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', refusal)
    publishWorkList([], 0)
    execution.setVariable('previousFileIds', '[]')
    return
}

// --- Refresh the Files preview (the whole answer is in hand) ------------------------

def workItems = decodeWorkList((execution.getVariable('importWorkList') ?: '').toString())
int skippedNoId = (execution.getVariable('rerunSkippedNoId') ?: 0) as int
refreshQueryPreview(queryId, ids.filesAttrTypeId, workItems, workItems.size() + skippedNoId)

loggerApi.info("Rerun of '${conditionName}': ${createNew ? "creating assets for up to ${newFiles} new file(s)" : 'refreshing the files already in Collibra only'}")
