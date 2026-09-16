// import_batch.groovy  (on-prem variant)
//
// Second stage of the gated asset import: an ASYNC script task that Collibra
// re-executes in a loop (exclusive gateway on ${hasMoreWork}) until the work
// list is drained. Each execution is one DB transaction that processes one
// batch of work items — the shape Collibra's bulk-operations guidance
// prescribes for high-volume workflows, so a 10k import never holds one giant
// transaction. The upsert itself is shared/file_batch.groovy (also used by the
// rerun workflow's sync_files_batch.groovy).
//
// A failing batch is logged, counted into importFailedCount, and the cursor
// STILL advances — one bad batch must never wedge the loop.
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
