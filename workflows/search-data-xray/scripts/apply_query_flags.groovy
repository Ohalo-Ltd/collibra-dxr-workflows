// apply_query_flags.groovy  (both editions)
//
// Runs right after the Search Results task, whatever the user chose about
// importing, and records the two sync options on the query asset as tags:
//   – keepInSync ticked         → tag dataxray-keep-in-sync (the nightly file
//                                 sync reruns this search every night)
//   – autoImportNewFiles UNticked → tag dataxray-no-auto-import (reruns refresh
//                                 the file assets already in Collibra but create
//                                 none for new matching files)
// Both are plain tags, and every manual rerun shows them again on its Rerun
// Options task (v2.10), so they can be changed at any time. Keeping in sync
// does not require importing now: a search can be kept in sync from day one
// and still only refresh what was imported later. The writing is shared with
// the rerun (applyQueryFlags, shared/query_flags.groovy).
//
// Synchronous, so it runs as the person who completed the results task.
// Never throws: a tag that can't be written is logged, the import continues.
//
// Reads: queryAssetId, keepInSync, autoImportNewFiles

// {{include:dxr_model.groovy}}
// {{include:query_flags.groovy}}

def rawId = (execution.getVariable('queryAssetId') ?: '').toString()
if (rawId.isEmpty()) {
    loggerApi.warn('Search DataXray: no query asset recorded — sync options not applied')
    return
}
applyQueryFlags(string2Uuid(rawId), execution.getVariable('keepInSync') == true,
                execution.getVariable('autoImportNewFiles') != false)
