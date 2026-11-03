// query_flags.groovy — a sync filter's sync options, and what they mean for
// the file assets it returns.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// The options live on the sync filter (query) asset as two tags:
//   dataxray-keep-in-sync    – the nightly file sync reruns this search
//   dataxray-no-auto-import  – reruns refresh the file assets already in
//                              Collibra but create none for new matches
// The Search Results task sets them when a search is created, and (v2.10) the
// Rerun Options task of every manual rerun shows them again and writes the
// user's choice back, so they can be changed at any time without recreating
// the search.
//
// Each file asset says whether it is kept in sync in its DataXray Sync
// attribute (syncModeAttrOn, dxr_model.groovy). The rule: a file is "Kept in
// sync nightly" when at least one sync filter that still returns it is kept in
// sync nightly — sync wins over one-time import — and "One-time import"
// otherwise (including a file no search returns any more: nothing refreshes it).

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}

// Write a sync filter's two options as tags, adding or removing each only when
// it differs. Never throws (a tag that can't be written is logged). Returns the
// tags as they now are: [keepInSync:, noAutoImport:].
def applyQueryFlags(UUID queryId, boolean keepInSync, boolean autoImportNewFiles) {
    def tags = assetTagNames(queryId)
    boolean hasKeep = tags.contains(dxrKeepInSyncTag())
    boolean hasNoAuto = tags.contains(dxrNoAutoImportTag())
    if (keepInSync && !hasKeep) {
        if (addAssetTagQuietly(queryId, dxrKeepInSyncTag(),
                "Tagged sync filter ${queryId} with ${dxrKeepInSyncTag()} — the nightly file sync will rerun this search")) { hasKeep = true }
    } else if (!keepInSync && hasKeep) {
        if (removeAssetTagQuietly(queryId, dxrKeepInSyncTag(),
                "Removed ${dxrKeepInSyncTag()} from sync filter ${queryId} — the nightly file sync no longer reruns this search")) { hasKeep = false }
    }
    if (!autoImportNewFiles && !hasNoAuto) {
        if (addAssetTagQuietly(queryId, dxrNoAutoImportTag(),
                "Tagged sync filter ${queryId} with ${dxrNoAutoImportTag()} — reruns will refresh existing file assets only")) { hasNoAuto = true }
    } else if (autoImportNewFiles && hasNoAuto) {
        if (removeAssetTagQuietly(queryId, dxrNoAutoImportTag(),
                "Removed ${dxrNoAutoImportTag()} from sync filter ${queryId} — reruns will create assets for new matching files again")) { hasNoAuto = false }
    }
    return [keepInSync: hasKeep, noAutoImport: hasNoAuto]
}

// Whether the nightly file sync reruns this sync filter: the same test the
// nightly driver applies — tagged dataxray-keep-in-sync, in the DataXray Sync
// Filter domain, and not Obsolete (archiving is the supported pause). `cache`
// (a Map the caller keeps for one batch or run) saves the lookups per filter;
// a filter that can't be read (deleted) counts as not synced.
def querySyncedNightly(UUID queryId, Map ids, Map cache) {
    if (cache.containsKey(queryId)) { return cache[queryId] }
    boolean synced = false
    try {
        def q = assetApi.getAsset(queryId)
        synced = q.getStatus()?.getId() != ids.obsoleteStatusId &&
            q.getDomain()?.getId() == ids.queryDomainId &&
            assetTagNames(queryId).contains(dxrKeepInSyncTag())
    } catch (Exception lookupEx) {
        loggerApi.warn("Could not read sync filter ${queryId} for the DataXray Sync attribute: ${lookupEx.message}")
    }
    cache[queryId] = synced
    return synced
}

// The DataXray Sync value of a file returned by these sync filters.
def fileSyncModeFor(Collection queryIds, Map ids, Map cache) {
    return queryIds.any { querySyncedNightly(it as UUID, ids, cache) } ? dxrSyncModeKeptInSync() : dxrSyncModeOneTime()
}

// Write a file asset's DataXray Sync value (nothing while Configure has not
// created the attribute type). Never throws.
def writeFileSyncMode(UUID assetId, String mode, Map ids) {
    if (!syncModeAttrOn()) { return }
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(assetId)
            .typeId(ids.syncModeAttrTypeId)
            .values([mode] as List<Object>)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("Could not set DataXray Sync on file asset ${assetId}: ${attrEx.message}")
    }
}

// Recompute the DataXray Sync value of these file assets (UUIDs or their
// strings) from the sync filters that return them now. For the files a run did
// not get to after its options changed (rerun_common.groovy,
// refreshSyncModesAfterOptionChange). Never throws; returns how many were written.
def refreshFileSyncModes(Collection assetIds, Map ids) {
    if (!syncModeAttrOn()) { return 0 }
    def cache = [:]
    int written = 0
    assetIds.each { raw ->
        try {
            def assetId = raw instanceof UUID ? raw : string2Uuid(raw.toString())
            def sources = relationIdsByFarEnd(ids.returnsRelationTypeId, null, assetId).keySet()
            writeFileSyncMode(assetId, fileSyncModeFor(sources, ids, cache), ids)
            written++
        } catch (Exception refreshEx) {
            loggerApi.warn("Could not recompute DataXray Sync on file asset ${raw}: ${refreshEx.message}")
        }
    }
    return written
}
