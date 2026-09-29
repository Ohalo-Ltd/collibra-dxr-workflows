// import_collector.groovy  (both editions)
//
// Runs synchronously after the user accepts "Import all N results as assets?".
// There is no work list in this variant: the import loop fetches one page of
// results per transport task and upserts it as one batch
// (import_page.groovy). This task re-checks the instance-wide cap (the
// form can be bypassed via REST), builds the classification index from Collibra
// (public uuid + numeric index id per asset), tags the query for the nightly
// sync when asked, zeroes the counters and arms page 0 of the results (same
// query_items as the preview, fresh point-in-time).

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:dxr_search.groovy}}

def ids = dxrModelIds()

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
def keepInSync   = execution.getVariable('keepInSync') == true
int total        = (execution.getVariable('resultCount') ?: 0) as int
// Start no larger than the preview ended up using: if Collibra refused its
// rows as too large at the configured size, the import pages would be too.
// (Not dxrPageSizeCur: the results-file pass ran since, with slimmer rows.)
int configuredPageSize = clampPageSize(execution.getVariable('dataxrayPageSize'))
def previewSize  = execution.getVariable('dxrPreviewPageSize')
int pageSize     = Math.min(configuredPageSize, previewSize != null && previewSize.toString().isInteger() ? previewSize.toString().toInteger() : configuredPageSize)

// --- Re-check the import limits -------------------------------------------

// The results form hides the import controls above these limits, but a task can
// be completed through the REST API, so the script enforces them too.
if (total > dxrMaxImportFiles()) {
    def msg = dxrTooManyToImportMessage(total)
    loggerApi.error(msg)
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Data X-Ray import refused')
    wf.setUserMessage(msg)
    throw wf
}

int filesDomainCount = countAssetsInDomain(ids.filesDomainId)
int projectedTotal = filesDomainCount + total
if (projectedTotal > dxrMaxTotalFileAssets()) {
    def msg = "Import refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this search matched ${total} — the projected ${projectedTotal} would exceed the ${dxrMaxTotalFileAssets()} instance-wide limit."
    loggerApi.error(msg)
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Data X-Ray import refused')
    wf.setUserMessage(msg)
    throw wf
}

// --- Classification index (built once from Collibra; the page task resolves against it)

execution.setVariable('classIndex', JsonOutput.toJson(buildSearchClassificationIndex(ids)))

// --- Counters + first page ----------------------------------------------------------

zeroImportCounters(total, pageSize)
execution.setVariable('dxrFetchComplete', false)
execution.setVariable('importAborted', false)
def items = readJsonVariable('dxrQueryItems', [])
beginFilesPass()
startFilesPage(items, 0, pageSize, null)
loggerApi.info("Import ready: ${total} file(s) in pages of ${pageSize}; files domain currently holds ${filesDomainCount}")

// --- Keep-in-sync flag ----------------------------------------------------------

if (keepInSync) {
    addAssetTagQuietly(queryAssetId, dxrKeepInSyncTag(),
        "Tagged query asset ${queryAssetId} with ${dxrKeepInSyncTag()} — the nightly file sync will rerun this search")
}
