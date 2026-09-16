// import_collector.groovy  (on-prem variant: direct HTTPS to Data X-Ray)
//
// First stage of the gated asset import: runs synchronously after the user
// accepts "Import all N results as assets?" on the search results form.
//
// Responsibilities:
//   1. Re-check the instance-wide import cap (the form's visibility rules can
//      be bypassed by completing the task via the REST API, so the script is
//      the enforcement point).
//   2. Re-run the Data X-Ray query (streaming NDJSON, exactly like the search
//      task). The preview/import gap can drift a little if Data X-Ray changed
//      in between; the import summary reports what was actually imported.
//   3. Distill every matching file into a compact work-item tuple
//      (shared/dxr_rows.groovy) and store the whole list gzip+Base64-encoded in
//      ONE immutable process variable (importWorkList). The async batch task
//      (import_batch.groovy) then consumes it 50 rows at a time, advancing only
//      an integer cursor — the blob itself is written once, here, and never
//      rewritten.
//   4. Resolve each file's matched classifications to Collibra asset UUIDs up
//      front (by Data X-Ray ID first, then by asset name), so the batch task
//      does no lookups at all.
//   5. Tag the query asset 'dataxray-keep-in-sync' when the user ticked
//      "keep in sync" (the nightly file sync picks flagged queries up).
//
// Process variables produced:
//   importWorkList     (String)  – gzip+Base64 JSON array of work-item tuples
//   importTotal        (Integer) – number of work items
//   importCursor       (Integer) – 0
//   hasMoreWork        (Boolean) – importTotal > 0
//   importCreatedCount / importUpdatedCount / importFailedCount /
//   importRelationCount / importSkippedClassifications (Integer) – zeroed here,
//       advanced by import_batch.groovy
//   importBatchCount   (Integer) – total number of batches (for progress logs)

import com.collibra.dgc.workflow.api.exception.WorkflowException

// {{include:dxr_model.groovy}}
// {{include:dxr_config.groovy}}
// {{include:dxr_http_direct.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:worklist.groovy}}

def ids = dxrModelIds()

// --- Inputs from earlier tasks ------------------------------------------------

def dataxrayUrl       = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
def dataxrayAuthToken = (execution.getVariable('dataxrayAuthToken') ?: '').toString().trim()
def queryString       = (execution.getVariable('queryString') ?: '').toString()
if (queryString == '(all files)') { queryString = '' }
def queryAssetId      = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
def keepInSync        = execution.getVariable('keepInSync') == true

// --- Stream the query results into the work list ------------------------------

def searchUrl = dxrFilesUrl(dataxrayUrl, queryString)

def FETCH_ATTEMPTS = 3
def workItems = []
int skippedNoId = 0
try {
    def collected = withDxrRetries(FETCH_ATTEMPTS, 5000, 'Data X-Ray fetch') {
        def items = []
        int skipped = 0
        streamDxrNdjson(searchUrl, dataxrayAuthToken, 120_000) { row ->
            def tuple = extractFileTuple(row, dataxrayUrl)
            if (tuple == null) { skipped++ } else { items << tuple }
        }
        return [items: items, skipped: skipped]
    }
    workItems = collected.items
    skippedNoId = collected.skipped
} catch (Exception fetchEx) {
    loggerApi.error("Import aborted — could not fetch results from Data X-Ray after ${FETCH_ATTEMPTS} attempts: ${fetchEx.message}")
    def wf = new WorkflowException("Data X-Ray file import failed while fetching results: ${fetchEx.message}", fetchEx)
    wf.setTitleMessage('Data X-Ray import failed')
    wf.setUserMessage("Could not fetch the search results from Data X-Ray to import them (${FETCH_ATTEMPTS} attempts): ${fetchEx.message}")
    throw wf
}
if (skippedNoId > 0) {
    loggerApi.warn("Import: skipped ${skippedNoId} result row(s) without a usable file id")
}

// --- Re-check the instance-wide cap -------------------------------------------

int filesDomainCount = countAssetsInDomain(ids.filesDomainId)
int projectedTotal = filesDomainCount + workItems.size()
if (projectedTotal > dxrMaxTotalFileAssets()) {
    def msg = "Import refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this search matched ${workItems.size()} — the projected ${projectedTotal} would exceed the ${dxrMaxTotalFileAssets()} instance-wide limit."
    loggerApi.error(msg)
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Data X-Ray import refused')
    wf.setUserMessage(msg)
    throw wf
}

// --- Resolve classifications to Collibra asset UUIDs --------------------------

def classIndex = buildClassificationIndex(ids.classificationsDomainId, ids.dataxrayIdAttrTypeId)
int unresolvedClassifications = resolveTupleClassifications(workItems, classIndex)
if (unresolvedClassifications > 0) {
    loggerApi.warn("Import: ${unresolvedClassifications} classification reference(s) could not be resolved to Collibra assets (run Sync Data X-Ray Classifications and re-import to link them)")
}

// --- Store the work list -------------------------------------------------------

int batchCount = publishWorkList(workItems, unresolvedClassifications)
loggerApi.info("Import collector ready: ${workItems.size()} file(s) in ${batchCount} batch(es) of ${dxrBatchSize()}; files domain currently holds ${filesDomainCount}")

// --- Keep-in-sync flag ----------------------------------------------------------

if (keepInSync) {
    addAssetTagQuietly(queryAssetId, dxrKeepInSyncTag(),
        "Tagged query asset ${queryAssetId} with ${dxrKeepInSyncTag()} — the nightly file sync will rerun this search")
}
