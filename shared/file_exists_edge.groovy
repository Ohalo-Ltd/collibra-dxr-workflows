// file_exists_edge.groovy — the nightly check that DataXray still has the files
// no saved search returns (Collibra Cloud + Edge edition).
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
//   – a 'Not in DataXray' asset found again → back to 'Not in any search'.
// A search that matches the file again sets it to Candidate as usual
// (isParkedFileStatus). A status a person set is never touched: only assets
// whose status is one of these two are looked at, and each is re-read just
// before it is changed.
//
// DataXray updates a datasource's index in place while it scans (file by file,
// only files that changed), so a file never drops out of the index mid-scan:
// not found means gone, no guard against rescans is needed.
//
// One request per datasource and up to dxrExistsCheckBatchSize() object ids:
// query_items = (datasource_id = D) AND (object_id = o1 OR o2 OR …), returning
// only datasource_id and object_id. Assets without an object key (imported by
// a release before v2.6 and not synced since) are counted and skipped.
//
// Work list: [[ds: '<datasource id>', items: [[assetId, objectId], …]], …],
// gzip+Base64 in existsWork; existsChunk is the cursor.

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

// Only the two fields the check reads come back (~250 bytes per file found).
def edgeExistsExcludedFields() {
    return ['dxr#*', 'metadata#*', 'annotation*', 'extracted_metadata#*', 'computed*', 'ai#*',
            'external*', 'folder_id', 'ds#*', 'entitlement*']
}

def armExistsCheckRequest(Map chunk) {
    def items = [edgeQueryItem('datasource_id', chunk.ds, 'number', 'exact', 'AND', 0, 0)]
    chunk.items.eachWithIndex { item, i ->
        items << edgeQueryItem('object_id', item[1], 'text', 'exact', i == 0 ? 'AND' : 'OR', 1, i)
    }
    def body = [mode: 'DXR_JSON_QUERY', datasourceIds: [], pageNumber: 0, pageSize: chunk.items.size(),
                filter: [query_items: items], sort: edgeSearchSort(), excludedFields: edgeExistsExcludedFields()]
    armEdgeRequest('POST', '/api/indexed-files/search', groovy.json.JsonOutput.toJson(body))
}

// Apply one answer to its chunk. Returns [gone: n, back: n, kept: n].
def applyExistsCheckPage(Map chunk, Map page, Map ids) {
    def result = [gone: 0, back: 0, kept: 0]
    if (page.total > page.hits.size()) {
        // More matches than asked about (object ids repeated within the
        // datasource?): an id missing from this page might be on the next one.
        loggerApi.warn("Existence check, datasource ${chunk.ds}: DataXray matched ${page.total} file(s) for ${chunk.items.size()} object id(s); this batch is left unchanged")
        result.kept = chunk.items.size()
        return result
    }
    def found = [] as Set
    page.hits.each { hit ->
        def src = hit?._source ?: [:]
        if ((src.datasource_id ?: '').toString() == chunk.ds.toString() && src.object_id != null) {
            found << src.object_id.toString()
        }
    }
    chunk.items.each { item ->
        def assetId = string2Uuid(item[0].toString())
        try {
            if (!assetApi.exists(assetId)) { return }
            def asset = assetApi.getAsset(assetId)
            def sid = asset.getStatus()?.getId()
            def target = null
            if (found.contains(item[1].toString())) {
                if (sid == ids.notInDataXrayStatusId) { target = ids.notInAnySearchStatusId }
            } else if (sid == ids.notInAnySearchStatusId) {
                target = ids.notInDataXrayStatusId
            }
            if (target == null) { result.kept++; return }
            assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                .id(assetId).statusId(target).build())
            if (target == ids.notInDataXrayStatusId) {
                result.gone++
                loggerApi.info("File asset '${asset.getName()}' [${assetId}] set to 'Not in DataXray': DataXray has no file ${item[1]} in datasource ${chunk.ds}")
            } else {
                result.back++
                loggerApi.info("File asset '${asset.getName()}' [${assetId}] is in DataXray again: set back to 'Not in any search'")
            }
        } catch (Exception changeEx) {
            loggerApi.error("Existence check: could not update file asset ${assetId}: ${changeEx.message}")
        }
    }
    return result
}
