// import_collector_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs synchronously after the user accepts "Import all N results as assets?".
// There is no work list in this variant: the import loop fetches one page of
// results per External API task and upserts it as one batch
// (import_page_edge.groovy). This task re-checks the instance-wide cap (the
// form can be bypassed via REST), builds the classification index once, tags
// the query for the nightly sync when asked, zeroes the counters and arms
// page 0 of the results (same query_items as the preview, fresh point-in-time).

import com.collibra.dgc.workflow.api.exception.WorkflowException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:dxr_edge.groovy}}

def ids = dxrModelIds()

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
def keepInSync   = execution.getVariable('keepInSync') == true
int total        = (execution.getVariable('resultCount') ?: 0) as int
int pageSize     = edgePageSize(execution.getVariable('dataxrayPageSize'), 50)

// --- Re-check the instance-wide cap -------------------------------------------

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

// --- Classification index (built once; the page task resolves against it) ------

def classIndex = buildClassificationIndex(ids.classificationsDomainId, ids.dataxrayIdAttrTypeId)
execution.setVariable('classIndex', JsonOutput.toJson([
    byDxrId: classIndex.byDxrId.collectEntries { k, v -> [(k.toString()): v.toString()] },
    byName : classIndex.byName.collectEntries  { k, v -> [(k.toString()): v.toString()] },
]))

// --- Counters + first page ----------------------------------------------------------

zeroEdgeImportCounters(total, pageSize)
execution.setVariable('dxrFetchMode', 'import')
execution.setVariable('dxrFetchComplete', false)
execution.setVariable('importAborted', false)
def items = new JsonSlurper().parseText((execution.getVariable('dxrQueryItems') ?: '[]').toString()) as List
startEdgeFilesPage(items, 0, pageSize, null)
loggerApi.info("Import ready: ${total} file(s) in pages of ${pageSize}; files domain currently holds ${filesDomainCount}")

// --- Keep-in-sync flag ----------------------------------------------------------

if (keepInSync) {
    addAssetTagQuietly(queryAssetId, dxrKeepInSyncTag(),
        "Tagged query asset ${queryAssetId} with ${dxrKeepInSyncTag()} — the nightly file sync will rerun this search")
}
