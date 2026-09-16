// sync_files_batch.groovy  (on-prem variant)
//
// Second stage of the file sync: an ASYNC script task that Collibra
// re-executes in a loop (exclusive gateway on ${hasMoreWork}) until the work
// list produced by rerun_collector.groovy is drained. Each execution is one DB
// transaction that processes one batch of work items. The upsert itself is
// shared/file_batch.groovy (also used by the search workflow's
// import_batch.groovy — this script is its rerun twin). An aborted rerun
// leaves importTotal at 0, so the early guard below makes this task a no-op.
//
// Reads:  importWorkList (immutable), importCursor, importTotal, queryAssetId
// Writes: importCursor, hasMoreWork, importCreatedCount, importUpdatedCount,
//         importFailedCount, importRelationCount, importRelationsRemovedCount

// {{include:dxr_model.groovy}}
// {{include:worklist.groovy}}
// {{include:file_batch.groovy}}

int total = (execution.getVariable('importTotal') ?: 0) as int
if (total == 0) {
    // Nothing to do (empty result set, or an aborted rerun) — end the loop.
    execution.setVariable('hasMoreWork', false)
    return
}

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
int cursor = (execution.getVariable('importCursor') ?: 0) as int
int batchCount = (execution.getVariable('importBatchCount') ?: 0) as int

def workItems = decodeWorkList((execution.getVariable('importWorkList') ?: '').toString())
int end = Math.min(cursor + dxrBatchSize(), workItems.size())
def batch = workItems.subList(cursor, end)
int batchNumber = (int) (cursor / dxrBatchSize()) + 1

def r = processFileBatch(batch, queryAssetId, "Import batch ${batchNumber}/${batchCount}".toString())

// --- Advance the cursor ----------------------------------------------------------

execution.setVariable('importCursor', end)
execution.setVariable('hasMoreWork', end < workItems.size())
accumulateBatchCounters(r)

loggerApi.info("Import batch ${batchNumber}/${batchCount}: +${r.created} created, +${r.updated} updated, +${r.failed} failed, +${r.relationsAdded}/-${r.relationsRemoved} relation(s); ${end}/${total} done")
