// apply_query_flags.groovy  (both editions)
//
// Runs right after the Search Results task, whatever the user chose about
// importing, and records the two sync options on the query asset as tags:
//   – keepInSync ticked         → tag dataxray-keep-in-sync (the nightly file
//                                 sync reruns this search every night)
//   – autoImportNewFiles UNticked → tag dataxray-no-auto-import (reruns refresh
//                                 the file assets already in Collibra but create
//                                 none for new matching files)
// Both are plain tags, so they can be added or removed on the query asset at
// any time. Keeping in sync no longer requires importing now: a search can be
// kept in sync from day one and still only refresh what was imported later.
//
// Synchronous, so it runs as the person who completed the results task.
// Never throws: a tag that can't be written is logged, the import continues.
//
// Reads: queryAssetId, keepInSync, autoImportNewFiles

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}

def rawId = (execution.getVariable('queryAssetId') ?: '').toString()
if (rawId.isEmpty()) {
    loggerApi.warn('Search DataXray: no query asset recorded — sync options not applied')
    return
}
def queryAssetId = string2Uuid(rawId)

if (execution.getVariable('keepInSync') == true) {
    addAssetTagQuietly(queryAssetId, dxrKeepInSyncTag(),
        "Tagged query asset ${queryAssetId} with ${dxrKeepInSyncTag()} — the nightly file sync will rerun this search")
}
if (execution.getVariable('autoImportNewFiles') == false) {
    addAssetTagQuietly(queryAssetId, dxrNoAutoImportTag(),
        "Tagged query asset ${queryAssetId} with ${dxrNoAutoImportTag()} — reruns will refresh existing file assets only")
}
