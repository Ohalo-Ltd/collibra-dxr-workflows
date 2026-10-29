// file_exists_edge.groovy — asking DataXray whether it still has given file
// assets, and refreshing the ones it has (Collibra Cloud + Edge edition).
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// One page loop (handleEdgeFilesPage), one set of counters, three callers:
//   – mode 'nightly': the nightly file sync, after its reruns and orphan
//     sweep, for EVERY file asset no search returns ('Not in any search' /
//     'Not in DataXray');
//   – mode 'orphans': the end of a Cloud + Edge rerun, for the files that
//     rerun just stopped returning — so a file deleted in DataXray is 'Not in
//     DataXray', and a file that merely stopped matching has its classification
//     links brought up to date, when the rerun ends rather than the next night
//     (TRC stories #28/#29/#31, Oct 2026);
//   – mode 'zeroResults': the end of a rerun that got 0 results, for every
//     file the search returned before (see below).
//
// A file asset no search returns either still exists in DataXray and just
// stopped matching, or was deleted there. A search can't tell the two apart,
// so each such asset is looked up by its object key (DataXray Datasource ID +
// DataXray Object ID), the key that survives an index drop and rescan:
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
// (isParkedFileStatus). A status a person set is never touched: in the
// 'nightly' and 'orphans' modes only assets whose status is one of these two
// are looked at, and each is re-read just before it is changed.
//
// Mode 'zeroResults': a rerun that gets 0 results never unlinks anything on
// that answer alone (an empty answer is far more often a DataXray or
// connection problem than a search that really matches nothing). Instead
// every file the search returned before is looked up. Found files are
// refreshed (attributes, classification links) and keep their status and
// links. Only when DataXray answered every question and has NONE of them
// (every one had an object key, every answer was read) is the search's whole
// population really gone — a datasource deleted in DataXray — and then each
// file is unlinked (formerly returns) and, when no other search returns it,
// set to 'Not in DataXray' (statuses people set are kept). Otherwise nothing
// is changed, and the rerun summary says how many DataXray still has
// (concludeZeroResultsCheck).
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
// Process variables (reset by prepareExistsCheck; audit, and the rerun summary):
//   existsMode            – 'nightly' | 'orphans' | 'zeroResults'
//   existsCandidates      – file assets to check; existsNoKey of them skipped (no object key)
//   existsNotInDataXray   – set to 'Not in DataXray'
//   existsBackInDataXray  – back to 'Not in any search'
//   existsRefreshed       – found in DataXray and refreshed
//   existsLinksAdded / existsLinksRemoved – classification links added / swapped to 'formerly groups'
//   existsRowsSkipped     – rows too large to fetch, left as they are
//   existsFound           – distinct candidates DataXray returned
//   existsComplete        – every chunk was read to the end (false while running, or after a failure)
// Work list: [[ds: '<datasource id>', items: [[assetId, objectId], …]], …],
// gzip+Base64 in existsWork; existsChunk is the cursor. Per chunk: existsSeen
// (JSON list of the asset ids found so far), existsUnsafe (a row could not be
// read, so nothing in the chunk is flagged gone); existsUnsafeAny remembers
// that across chunks.

// {{include:dxr_model.groovy}}
// {{include:dxr_edge.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:file_batch.groovy}}

def existsCounterNames() {
    return ['existsNotInDataXray', 'existsBackInDataXray', 'existsRefreshed', 'existsLinksAdded',
            'existsLinksRemoved', 'existsRowsSkipped', 'existsFound']
}

def existsModeIs(String mode) {
    return (execution.getVariable('existsMode') ?: '').toString() == mode
}

// The work list: [chunks: List, candidates: int, noKey: int]. `assetIds` null →
// every file asset whose status is Not in any search / Not in DataXray;
// otherwise those assets (ones Collibra no longer has are dropped), kept when
// `eligible` (status id → boolean) is null or true.
def buildExistsCheckWork(Map ids, Collection assetIds = null, Closure eligible = null) {
    def candidates = [] as Set
    if (assetIds == null) {
        eachFileAsset(ids) { asset ->
            def sid = asset.getStatus()?.getId()
            if (sid == ids.notInAnySearchStatusId || sid == ids.notInDataXrayStatusId) {
                candidates << asset.getId().toString()
            }
        }
    } else {
        assetIds.each { aid ->
            def assetId = string2Uuid(aid.toString())
            if (!assetApi.exists(assetId)) { return }
            if (eligible == null || eligible(assetApi.getAsset(assetId).getStatus()?.getId())) {
                candidates << assetId.toString()
            }
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

// Reset the counters, build the work list and arm the first request. Returns
// true when a request was armed (hasMoreWork), false when there is nothing to
// do or the check could not be prepared. Never throws.
// opts: mode (String), assetIds (Collection, null = scan all), eligible
//       (Closure status id → boolean, optional), label (String, log prefix).
def prepareExistsCheck(Map ids, Map opts) {
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('existsMode', opts.mode.toString())
    execution.setVariable('existsComplete', false)
    execution.setVariable('existsUnsafeAny', false)
    execution.setVariable('existsCandidates', 0)
    execution.setVariable('existsNoKey', 0)
    execution.setVariable('existsChunkCount', 0)
    existsCounterNames().each { execution.setVariable(it, 0) }
    try {
        if (!statusApi.exists(ids.notInDataXrayStatusId)) {
            loggerApi.warn("${opts.label} skipped: the status 'Not in DataXray' does not exist. Run Configure DataXray Workflows once after upgrading.")
            return false
        }
        def work = buildExistsCheckWork(ids, opts.assetIds as Collection, opts.eligible as Closure)
        execution.setVariable('existsCandidates', work.candidates)
        execution.setVariable('existsNoKey', work.noKey)
        if (work.noKey > 0) {
            loggerApi.info("${opts.label}: ${work.noKey} file asset(s) have no DataXray Datasource ID / Object ID yet and are not checked (a rerun of a search that returns them stores both)")
        }
        if (work.chunks.isEmpty()) {
            loggerApi.info("${opts.label}: nothing to check (${work.candidates} file asset(s))")
            execution.setVariable('existsComplete', true)
            return false
        }
        loggerApi.info("${opts.label}: ${work.candidates - work.noKey} file asset(s) in ${work.chunks.size()} batch(es)")
        execution.setVariable('classIndex', groovy.json.JsonOutput.toJson(buildEdgeClassificationIndex(ids)))
        execution.setVariable('dxrDatasourceNames', '{}')
        execution.setVariable('existsWork', packJson(work.chunks))
        execution.setVariable('existsChunk', 0)
        execution.setVariable('existsChunkCount', work.chunks.size())
        startExistsCheckChunk(work.chunks[0] as Map)
        return true
    } catch (Exception prepEx) {
        loggerApi.error("${opts.label} skipped: ${prepEx.message}")
        execution.setVariable('hasMoreWork', false)
        return false
    }
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

// One ASYNC task after each existence-check External API task: drives the
// page loop over the current chunk; when the chunk has been read to the end,
// applies its verdict and arms the next chunk, or ends the loop (hasMoreWork
// false, existsComplete true). A failed request is retried twice; after that
// the check stops (hasMoreWork false, existsComplete false) — chunks already
// applied stay applied, the rest are checked by the next nightly file sync.
// Never throws.
def handleExistsCheckPage(Map ids, String labelPrefix) {
    int idx = (execution.getVariable('existsChunk') ?: 0) as int
    int count = (execution.getVariable('existsChunkCount') ?: 0) as int
    def what = "${labelPrefix} ${idx + 1}/${count}".toString()
    try {
        def chunks = unpackJson((execution.getVariable('existsWork') ?: '').toString()) as List
        def chunk = chunks[idx] as Map
        def classIndex = readJsonVariable('classIndex', [byDxrId: [:], byName: [:]])
        def opts = [
            label      : what,
            dataxrayUrl: (execution.getVariable('dataxrayUrl') ?: '').toString(),
            pageSize   : dxrEdgeDefaultPageSize(),
            index      : classIndex,
            onPage     : { Map page, List tuples -> refreshExistsPage(chunk, tuples, classIndex, ids) },
            onSkip     : { String fileId -> onExistsRowSkipped(chunk, fileId) },
            onFatal    : { String msg ->
                loggerApi.error("${labelPrefix} stopped at batch ${idx + 1}/${count} (${msg}); the remaining file assets are checked by the next nightly file sync")
                execution.setVariable('hasMoreWork', false)
                execution.setVariable('dxrFetchComplete', false)
            },
        ]
        handleEdgeFilesPage(opts)
        if (execution.getVariable('dxrFetchComplete') == true) {
            finishExistsCheckChunk(chunk, ids)
            if (idx + 1 < count) {
                execution.setVariable('existsChunk', idx + 1)
                startExistsCheckChunk(chunks[idx + 1] as Map)
            } else {
                execution.setVariable('hasMoreWork', false)
                execution.setVariable('existsComplete', true)
                loggerApi.info("${labelPrefix} done: ${execution.getVariable('existsRefreshed')} file asset(s) refreshed (+${execution.getVariable('existsLinksAdded')}/-${execution.getVariable('existsLinksRemoved')} classification link(s)), ${execution.getVariable('existsNotInDataXray')} set to 'Not in DataXray', ${execution.getVariable('existsBackInDataXray')} back to 'Not in any search'")
            }
        }
    } catch (Exception pageEx) {
        loggerApi.error("${what} failed: ${pageEx.message} [${dxrVersionLabel()}]")
        execution.setVariable('hasMoreWork', false)
    }
}

// The page loop's onPage handler: refresh every chunk asset a row of this page
// is about. In the 'zeroResults' mode every found asset is refreshed whatever
// its status, and no status changes. Never throws (per-asset catches); always
// continues to the next page.
def refreshExistsPage(Map chunk, List tuples, Map classIndex, Map ids) {
    boolean zeroMode = existsModeIs('zeroResults')
    def assetsByObject = [:].withDefault { [] }
    chunk.items.each { item -> assetsByObject[item[1].toString()] << item[0].toString() }
    resolveTupleClassifications(tuples, classIndex)
    def owned = integrationIdentityIds()
    boolean valuesOn = extractedValuesOn()
    def seen = readJsonVariable('existsSeen', []) as Set
    int found = 0
    int refreshed = 0
    int back = 0
    int linksAdded = 0
    int linksRemoved = 0
    tuples.each { tuple ->
        if (tuple[8].toString() != chunk.ds.toString()) { return }
        (assetsByObject.containsKey(tuple[9].toString()) ? assetsByObject[tuple[9].toString()] : []).each { aid ->
            if (seen.add(aid)) { found++ }
            def assetId = string2Uuid(aid)
            try {
                if (!assetApi.exists(assetId)) { return }
                def asset = assetApi.getAsset(assetId)
                def sid = asset.getStatus()?.getId()
                // Changed since the list was built (a rerun matched it, or a person
                // set a status): not this check's to refresh.
                if (!zeroMode && sid != ids.notInAnySearchStatusId && sid != ids.notInDataXrayStatusId) { return }
                writeFileAssetAttrs(assetId, tuple, ids, valuesOn)
                def links = reconcileFileGroups(assetId, tuple[7], ids, owned)
                linksAdded += links.added
                linksRemoved += links.removed
                if (links.added + links.removed > 0) {
                    loggerApi.info("File asset '${asset.getName()}' [${assetId}]: classification links refreshed from DataXray (+${links.added}/-${links.removed})")
                }
                refreshed++
                if (!zeroMode && sid == ids.notInDataXrayStatusId) {
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
    addToCounter('existsFound', found)
    addToCounter('existsRefreshed', refreshed)
    addToCounter('existsBackInDataXray', back)
    addToCounter('existsLinksAdded', linksAdded)
    addToCounter('existsLinksRemoved', linksRemoved)
    return true
}

// The page loop's onSkip handler: a row too large to fetch even alone. Its file
// still exists, but its asset is only known if the row's file id maps to one in
// the chunk; otherwise nothing in the chunk can be known to be gone.
def onExistsRowSkipped(Map chunk, String fileId) {
    addToCounter('existsRowsSkipped', 1)
    def aid = fileId ? deterministicFileAssetId(fileId).toString() : ''
    if (aid && chunk.items.any { it[0].toString() == aid }) {
        def seen = readJsonVariable('existsSeen', [])
        if (!seen.contains(aid)) {
            seen << aid
            addToCounter('existsFound', 1)
        }
        execution.setVariable('existsSeen', groovy.json.JsonOutput.toJson(seen))
    } else {
        execution.setVariable('existsUnsafe', true)
        execution.setVariable('existsUnsafeAny', true)
    }
}

// The chunk has been read to the end: every asset DataXray did not return is
// gone there. Returns the number set to 'Not in DataXray'. Nothing is flagged
// in the 'zeroResults' mode (its verdict is for the whole population at the
// end, concludeZeroResultsCheck).
def finishExistsCheckChunk(Map chunk, Map ids) {
    if (existsModeIs('zeroResults')) { return 0 }
    if (execution.getVariable('existsUnsafe') == true) {
        loggerApi.warn("Existence check, datasource ${chunk.ds}: a result could not be read, so none of this batch's ${chunk.items.size()} file asset(s) is set to 'Not in DataXray' this time")
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
    addToCounter('existsNotInDataXray', gone)
    return gone
}

// The 'zeroResults' verdict, once the check has ended. Returns
// [allGone:, unlinked:, gone:, found:, candidates:, noKey:, complete:, unsafe:].
// allGone (and so any change) requires: at least one candidate, every candidate
// with an object key, every chunk read to the end, no unreadable row, and not
// one candidate found. Then each candidate's 'returns' link from `queryAssetId`
// becomes 'formerly returns', and the asset — when no other search returns it
// and its status is one the integration owns — is set to 'Not in DataXray'.
def concludeZeroResultsCheck(Map ids, UUID queryAssetId) {
    def out = [allGone: false, unlinked: 0, gone: 0,
               found     : (execution.getVariable('existsFound') ?: 0) as int,
               candidates: (execution.getVariable('existsCandidates') ?: 0) as int,
               noKey     : (execution.getVariable('existsNoKey') ?: 0) as int,
               complete  : execution.getVariable('existsComplete') == true,
               unsafe    : execution.getVariable('existsUnsafeAny') == true]
    if (out.candidates == 0 || out.noKey > 0 || out.found > 0 || !out.complete || out.unsafe) { return out }
    out.allGone = true
    def chunks = unpackJson((execution.getVariable('existsWork') ?: '').toString()) as List
    def relationIdByTarget = [:]
    relationIdsByFarEnd(ids.returnsRelationTypeId, queryAssetId, null).each { targetId, relId ->
        relationIdByTarget[targetId.toString()] = relId
    }
    chunks.each { chunk ->
        (chunk.items as List).each { item ->
            def aid = item[0].toString()
            def assetId = string2Uuid(aid)
            try {
                def relId = relationIdByTarget[aid]
                if (relId != null) {
                    relationApi.removeRelation(relId)
                    out.unlinked++
                    addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                        .sourceId(queryAssetId).targetId(assetId).typeId(ids.formerlyReturnsRelationTypeId).build())
                }
                if (!assetApi.exists(assetId)) { return }
                def stillReturned = relationApi.findRelations(com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest.builder()
                    .relationTypeId(ids.returnsRelationTypeId)
                    .targetId(assetId)
                    .limit(1)
                    .build())
                if (!stillReturned.getResults().isEmpty()) { return }
                def asset = assetApi.getAsset(assetId)
                def sid = asset.getStatus()?.getId()
                if (sid == ids.notInDataXrayStatusId) { return }
                if (!isIntegrationOwnedFileStatus(sid, ids)) {
                    loggerApi.info("File asset '${asset.getName()}' [${assetId}] is no longer in DataXray; kept its status '${asset.getStatus()?.getName()}', set by a person")
                    return
                }
                assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                    .id(assetId).statusId(ids.notInDataXrayStatusId).build())
                out.gone++
                loggerApi.info("File asset '${asset.getName()}' [${assetId}] set to 'Not in DataXray': the search returned 0 results and DataXray has no file ${item[1]} in datasource ${chunk.ds}")
            } catch (Exception goneEx) {
                loggerApi.error("Existence check: could not unlink/update file asset ${assetId}: ${goneEx.message}")
            }
        }
    }
    addToCounter('existsNotInDataXray', out.gone)
    return out
}

def addToCounter(String name, int n) {
    execution.setVariable(name, ((execution.getVariable(name) ?: 0) as int) + n)
}
