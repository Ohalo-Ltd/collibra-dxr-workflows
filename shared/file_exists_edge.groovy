// file_exists_edge.groovy — the nightly check that DataXray still has the files
// no saved search returns, and the refresh of the ones it has (Collibra Cloud
// + Edge edition).
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// A file asset no search returns ('Not in any search') either still exists in
// DataXray and just stopped matching, or was deleted there. A search can't tell
// the two apart, so every night the files sync asks DataXray about each such
// asset by its object key (DataXray Datasource ID + DataXray Object ID), the
// key that survives an index drop and rescan:
//   – not found → 'Not in DataXray' (the file, or its whole datasource — a
//     datasource id DataXray no longer has simply matches nothing — is gone);
//   – found → the asset gets the refresh a rerun gives a file it returns,
//     minus the search link and the status: attributes (incl. DataXray
//     Extracted Values) and the file→classification links (reconcileFileGroups:
//     a label removed in DataXray becomes 'formerly groups'). Without this a
//     file that dropped out of every search — typically because the label the
//     search selects on was removed — kept its stale 'groups' links for good
//     (TRC review, 26 Oct 2026). A 'Not in DataXray' asset found again also
//     goes back to 'Not in any search'.
// A search that matches the file again sets it to Candidate as usual
// (isParkedFileStatus). A status a person set is never touched: only assets
// whose status is one of these two are looked at, and each is re-read just
// before it is changed.
//
// DataXray updates a datasource's index in place while it scans (file by file,
// only files that changed), so a file never drops out of the index mid-scan:
// not found means gone, no guard against rescans is needed.
//
// One query per datasource and up to dxrExistsCheckBatchSize() object ids
// (a "chunk"): query_items = (datasource_id = D) AND (object_id = o1 OR o2 OR …),
// walked with the rerun's page loop (handleEdgeFilesPage: full rows, paging,
// shrinking on Collibra's 100 KB refusal, datasource-name lookups). An asset
// is flagged 'Not in DataXray' only once its chunk has been read to the end.
// Assets without an object key (imported by a release before v2.6 and not
// synced since) are counted and skipped.
//
// Work list: [[ds: '<datasource id>', items: [[assetId, objectId], …]], …],
// gzip+Base64 in existsWork; existsChunk is the cursor. Per chunk: existsSeen
// (JSON list of the asset ids found so far), existsUnsafe (a row could not be
// read, so nothing in the chunk is flagged gone tonight).

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:file_batch.groovy}}

// The work list for tonight's check: [chunks: List, candidates: int, noKey: int].
def buildExistsCheckWork(Map ids) {
    def candidates = [] as Set
    eachFileAsset(ids) { asset ->
        def sid = asset.getStatus()?.getId()
        if (sid == ids.notInAnySearchStatusId || sid == ids.notInDataXrayStatusId) {
            candidates << asset.getId().toString()
        }
    }
    if (candidates.isEmpty()) { return [chunks: [], candidates: 0, noKey: 0] }
    def dsByAsset  = attributeValuesOfType(ids.datasourceIdAttrTypeId)
    def objByAsset = attributeValuesOfType(ids.objectIdAttrTypeId)
    def byDs = [:].withDefault { [] }
    int noKey = 0
    candidates.each { aid ->
        def ds = dsByAsset[aid]
        def obj = objByAsset[aid]
        if (ds && obj) { byDs[ds] << [aid, obj] } else { noKey++ }
    }
    def chunks = []
    byDs.each { ds, items ->
        items.collate(dxrExistsCheckBatchSize()).each { part -> chunks << [ds: ds, items: part] }
    }
    return [chunks: chunks, candidates: candidates.size(), noKey: noKey]
}

// Arm the first page of a chunk's query (the full-row field set a rerun uses).
def startExistsCheckChunk(Map chunk) {
    def items = [edgeQueryItem('datasource_id', chunk.ds, 'number', 'exact', 'AND', 0, 0)]
    chunk.items.eachWithIndex { item, i ->
        items << edgeQueryItem('object_id', item[1], 'text', 'exact', i == 0 ? 'AND' : 'OR', 1, i)
    }
    execution.setVariable('existsSeen', '[]')
    execution.setVariable('existsUnsafe', false)
    execution.setVariable('dxrQueryItems', groovy.json.JsonOutput.toJson(items))
    beginEdgeFilesPass()
    startEdgeFilesPage(items, 0, dxrEdgeDefaultPageSize(), null)
}

// The page loop's onPage handler: refresh every chunk asset a row of this page
// is about. Never throws (per-asset catches); always continues to the next page.
def refreshExistsPage(Map chunk, List tuples, Map classIndex, Map ids) {
    def assetsByObject = [:].withDefault { [] }
    chunk.items.each { item -> assetsByObject[item[1].toString()] << item[0].toString() }
    resolveTupleClassifications(tuples, classIndex)
    def owned = integrationIdentityIds()
    boolean valuesOn = extractedValuesOn()
    def seen = readJsonVariable('existsSeen', []) as Set
    int refreshed = 0
    int back = 0
    int linksAdded = 0
    int linksRemoved = 0
    tuples.each { tuple ->
        if (tuple[8].toString() != chunk.ds.toString()) { return }
        (assetsByObject.containsKey(tuple[9].toString()) ? assetsByObject[tuple[9].toString()] : []).each { aid ->
            seen << aid
            def assetId = string2Uuid(aid)
            try {
                if (!assetApi.exists(assetId)) { return }
                def asset = assetApi.getAsset(assetId)
                def sid = asset.getStatus()?.getId()
                // Changed since tonight's list was built (a rerun matched it, or a
                // person set a status): not this check's to refresh.
                if (sid != ids.notInAnySearchStatusId && sid != ids.notInDataXrayStatusId) { return }
                writeFileAssetAttrs(assetId, tuple, ids, valuesOn)
                def links = reconcileFileGroups(assetId, tuple[7], ids, owned)
                linksAdded += links.added
                linksRemoved += links.removed
                if (links.added + links.removed > 0) {
                    loggerApi.info("File asset '${asset.getName()}' [${assetId}]: classification links refreshed from DataXray (+${links.added}/-${links.removed})")
                }
                refreshed++
                if (sid == ids.notInDataXrayStatusId) {
                    assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                        .id(assetId).statusId(ids.notInAnySearchStatusId).build())
                    back++
                    loggerApi.info("File asset '${asset.getName()}' [${assetId}] is in DataXray again: set back to 'Not in any search'")
                }
            } catch (Exception refreshEx) {
                loggerApi.error("Existence check: could not refresh file asset ${assetId}: ${refreshEx.message}")
            }
        }
    }
    execution.setVariable('existsSeen', groovy.json.JsonOutput.toJson(seen as List))
    addToCounter('nightlyRefreshed', refreshed)
    addToCounter('nightlyBackInDataXray', back)
    addToCounter('nightlyLinksAdded', linksAdded)
    addToCounter('nightlyLinksRemoved', linksRemoved)
    return true
}

// The page loop's onSkip handler: a row too large to fetch even alone. Its file
// still exists, but its asset is only known if the row's file id maps to one in
// the chunk; otherwise nothing in the chunk can be known to be gone.
def onExistsRowSkipped(Map chunk, String fileId) {
    addToCounter('nightlyRefreshSkipped', 1)
    def aid = fileId ? deterministicFileAssetId(fileId).toString() : ''
    if (aid && chunk.items.any { it[0].toString() == aid }) {
        def seen = readJsonVariable('existsSeen', [])
        seen << aid
        execution.setVariable('existsSeen', groovy.json.JsonOutput.toJson(seen))
    } else {
        execution.setVariable('existsUnsafe', true)
    }
}

// The chunk has been read to the end: every asset DataXray did not return is
// gone there. Returns the number set to 'Not in DataXray'.
def finishExistsCheckChunk(Map chunk, Map ids) {
    if (execution.getVariable('existsUnsafe') == true) {
        loggerApi.warn("Existence check, datasource ${chunk.ds}: a result could not be read, so none of this batch's ${chunk.items.size()} file asset(s) is set to 'Not in DataXray' tonight")
        return 0
    }
    def seen = readJsonVariable('existsSeen', []) as Set
    int gone = 0
    chunk.items.each { item ->
        if (seen.contains(item[0].toString())) { return }
        def assetId = string2Uuid(item[0].toString())
        try {
            if (!assetApi.exists(assetId)) { return }
            def asset = assetApi.getAsset(assetId)
            if (asset.getStatus()?.getId() != ids.notInAnySearchStatusId) { return }
            assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                .id(assetId).statusId(ids.notInDataXrayStatusId).build())
            gone++
            loggerApi.info("File asset '${asset.getName()}' [${assetId}] set to 'Not in DataXray': DataXray has no file ${item[1]} in datasource ${chunk.ds}")
        } catch (Exception changeEx) {
            loggerApi.error("Existence check: could not update file asset ${assetId}: ${changeEx.message}")
        }
    }
    addToCounter('nightlyNotInDataXray', gone)
    return gone
}

def addToCounter(String name, int n) {
    execution.setVariable(name, ((execution.getVariable(name) ?: 0) as int) + n)
}
