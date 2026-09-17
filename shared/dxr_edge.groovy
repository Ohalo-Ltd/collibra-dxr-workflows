// dxr_edge.groovy — the Collibra Cloud + Edge transport to Data X-Ray.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// EDGE VARIANT ONLY. No HTTP is opened from Groovy: every call is a Collibra
// **External API task** (BPMN serviceTask flowable:type="http") routed through
// an Edge site's HTTP connection, which also holds the credentials. A script
// before the task "arms" the request in process variables; the task runs
// asynchronously; the script after it reads the response variables.
//
// Facts verified on Collibra Cloud 2026.09 / Edge 2026.6 (Sept 2026):
//   - <design:http-connection-name>${dataxrayConnectionName}</…> resolves an
//     expression to a connection on the Edge site; Basic auth on the connection
//     authenticates to Data X-Ray.
//   - requestUrl is a RELATIVE path appended to the connection's Host; method,
//     headers, path and body all accept expressions.
//   - The response body lands in <responseVariableName> as a String; with
//     saveResponseParameters, <prefix>ResponseStatusCode (Integer),
//     <prefix>ResponseReason and <prefix>ResponseHeaders are set too.
//   - Every HTTP status is "success": a 404 simply yields status 404.
//   - **Responses above 100 KB are refused** (Collibra support, Sept 2026; an
//     oversized body ends as <prefix>ErrorMessage "Response size exceeds the
//     allowed limit." and, with ignoreException=true, the workflow CONTINUES
//     with no body/status variables). Every request this file arms is designed
//     to stay well under that: results are paged (dxrEdgeDefaultPageSize rows),
//     catalogue objects are fetched per item, datasource names per id.
//   - One round trip costs ~0.5–2 s.
//
// Data X-Ray side: the pack uses DXR's internal search API,
// POST /api/indexed-files/search, because the public /api/v1/files endpoint
// streams the whole index and cannot be paged. Contract verified against
// demo.dataxray.io (Sept 2026) — see the pack CLAUDE.md "Edge edition" section.
//
// Classification ids: the index stores labels as numeric tag ids (dxr#tags),
// annotator hits under annotation_stats#unique_phrase_count.<numericId> and
// extractor output under extracted_metadata#<numericId>. Collibra holds the
// mapping numeric id → public UUID → asset in the "Data X-Ray Index ID" and
// "Data X-Ray ID" attributes, stamped by the Edge-edition classification sync
// (dxr_edge_sync.groovy). Search and rerun therefore need NO catalogue calls.

// {{include:dxr_rows.groovy}}
// {{include:dxr_query.groovy}}

// --- Arming requests and reading responses ------------------------------------

// Prime the next External API task: method, relative path, body, headers.
def armEdgeRequest(String method, String path, String body, String extraHeaders = '') {
    execution.setVariable('dxrMethod', method)
    execution.setVariable('dxrRequestPath', path)
    execution.setVariable('dxrRequestBody', body ?: '')
    def headers = method == 'GET'
        ? 'Accept: application/json'
        : 'Content-Type: application/json\nAccept: application/json'
    if (extraHeaders) { headers = headers + '\n' + extraHeaders }
    execution.setVariable('dxrRequestHeaders', headers)
}

// Read AND CLEAR what the External API task left behind, so a stale body can
// never be mistaken for a fresh one on a retry.
// Returns [ok: boolean, status: int (-1 when none), body: String, error: String].
def readEdgeResponse() {
    def status = execution.getVariable('dxrResponseStatusCode')
    def body   = execution.getVariable('dxrResponseBody')
    def err    = execution.getVariable('dxrErrorMessage')
    def reason = execution.getVariable('dxrResponseReason')
    ['dxrResponseBody', 'dxrResponseStatusCode', 'dxrResponseReason', 'dxrResponseHeaders',
     'dxrErrorMessage', 'dxrResponseProtocol'].each { n ->
        try {
            if (execution.hasVariable(n)) { execution.removeVariable(n) }
        } catch (Exception ignored) {
            // best-effort
        }
    }
    int code = status == null ? -1 : (status.toString().isInteger() ? status.toString().toInteger() : -1)
    def text = body == null ? '' : body.toString()
    if (err) {
        return [ok: false, status: code, body: text, error: "Edge request failed: ${err}".toString()]
    }
    if (code != 200) {
        def detail = truncateText(text, 300)
        return [ok: false, status: code, body: text,
                error: "Data X-Ray API returned HTTP ${code}${reason ? ' ' + reason : ''}${detail ? ': ' + detail : ''}".toString()]
    }
    if (text.trim().isEmpty()) {
        return [ok: false, status: code, body: text, error: 'Data X-Ray API returned an empty body']
    }
    return [ok: true, status: code, body: text, error: '']
}

// Retry bookkeeping for one armed request (the request stays armed; the loop
// simply runs the External API task again). Returns true while retries remain.
def edgeRetry(String what, String error) {
    int attempts = ((execution.getVariable('dxrPageAttempts') ?: 0) as int) + 1
    execution.setVariable('dxrPageAttempts', attempts)
    if (attempts < 3) {
        loggerApi.warn("${what} attempt ${attempts}/3 failed (${error}) — retrying")
        execution.setVariable('hasMoreWork', true)
        return true
    }
    loggerApi.error("${what} failed after ${attempts} attempts: ${error}")
    return false
}

def edgeResetRetries() {
    execution.setVariable('dxrPageAttempts', 0)
}

// The page size configuration variable, clamped to a sane range.
def edgePageSize(Object raw) {
    def s = raw == null ? '' : raw.toString().trim()
    if (!s.isInteger()) { return dxrEdgeDefaultPageSize() }
    return Math.max(1, Math.min(dxrEdgeMaxPageSize(), s.toInteger()))
}

// Read a JSON process variable into an object ('' / null → the fallback).
def readJsonVariable(String name, Object fallback) {
    def raw = execution.getVariable(name)
    if (raw == null || raw.toString().isEmpty()) { return fallback }
    return new groovy.json.JsonSlurper().parseText(raw.toString())
}

// --- The search request -------------------------------------------------------

// Fields dropped from every hit to keep a page well under the 100 KB response
// cap. annotation_stats#unique_phrase_count.<id> is kept as the per-annotator
// hit evidence; annotation.<id> (the phrases) is not needed.
def edgeExcludedFields() {
    return ['dxr#raw_text', 'annotations', 'annotation.*', 'annotation_stats#count.*', 'computed.*', 'ai#*',
            'external*', 'metadata#OWNER', 'metadata#WHO_CAN_ACCESS', 'metadata#CREATED_BY', 'metadata#MODIFIED_BY',
            'metadata#OWNER_GROUP*', 'metadata#binary_hash', 'dxr#sha_256*', 'folder_id', 'dxr#manually_removed_tags']
}

// A stable sort (same order the public export uses) so from/size paging under a
// point-in-time id never skips or repeats rows.
def edgeSearchSort() {
    return [[property: 'datasource_id', order: 'ASCENDING'], [property: 'ds#file_name.raw', order: 'ASCENDING']]
}

def buildEdgeSearchBody(List queryItems, int pageNo, int pageSize, String pitId) {
    def req = [mode: 'DXR_JSON_QUERY', datasourceIds: [], pageNumber: pageNo, pageSize: pageSize,
               filter: [query_items: queryItems], sort: edgeSearchSort(),
               excludedFields: edgeExcludedFields(), usePIT: true]
    if (pitId) { req.pitId = pitId }
    return groovy.json.JsonOutput.toJson(req)
}

// Arm one page of the files search.
def startEdgeFilesPage(List queryItems, int pageNo, int pageSize, String pitId) {
    execution.setVariable('dxrStage', 'files')
    execution.setVariable('dxrPageNo', pageNo)
    execution.setVariable('dxrPitId', pitId ?: '')
    armEdgeRequest('POST', '/api/indexed-files/search', buildEdgeSearchBody(queryItems, pageNo, pageSize, pitId))
    edgeResetRetries()
    execution.setVariable('hasMoreWork', true)
}

// Parse a search response. Returns [hits: List, total: int, pitId: String, maxResultWindow: int].
def parseEdgeSearchPage(String body) {
    def parsed = new groovy.json.JsonSlurper().parseText(body)
    def hits = parsed?.hits?.hits
    if (!(parsed instanceof Map) || !(hits instanceof List)) {
        throw new RuntimeException("Data X-Ray search response has no hits array; keys: ${parsed instanceof Map ? parsed.keySet() : parsed?.getClass()?.getSimpleName()}")
    }
    def totalNode = parsed.hits.total
    int total = totalNode instanceof Map ? ((totalNode.value ?: 0) as int) : ((totalNode ?: 0) as int)
    int window = (parsed.max_result_window ?: 10_000) as int
    return [hits: hits, total: total, pitId: (parsed.pit_id ?: '').toString(), maxResultWindow: window]
}

// --- Rows ----------------------------------------------------------------------

// Distill one search hit into the same work-item tuple dxr_rows.groovy defines
// for /api/v1/files rows. `index` is buildEdgeClassificationIndex()'s result
// (numeric id → public uuid per kind, names by uuid); `dsNames` maps datasource
// id → name. Classification refs are [uuid, name] pairs; a numeric id Collibra
// does not know becomes ['', 'kind#<id>'] so it counts as unresolved downstream
// (run the Edge-edition classification sync to teach Collibra about it).
def tupleFromSearchHit(Map hit, Map index, Map dsNames, String baseUrl) {
    def src = hit?._source instanceof Map ? hit._source : [:]
    def id = (hit?._id ?: '').toString()
    if (id.isEmpty()) { return null }

    def fileName = (src['ds#file_name'] ?: '').toString().replaceFirst('^/', '')
    def folders = src['ds#parent_folder_paths']
    def folder = (folders instanceof List && !folders.isEmpty()) ? (folders[0] ?: '').toString().replaceAll('/+$', '') : ''
    def path = folder.isEmpty() ? fileName : "${folder}/${fileName}".toString()
    def dsId = (src.datasource_id ?: '').toString()
    def datasource = (dsNames ?: [:])[dsId] ?: dsId
    def size = src['ds#file_size'] == null ? '' : src['ds#file_size'].toString()
    def modified = (src['metadata#MODIFIED_DATE'] ?: '').toString()

    def refs = []
    def ref = { Map byIndexId, String n, String kind ->
        def uuid = (byIndexId ?: [:])[n]
        refs << [(uuid ?: '').toString(), (uuid ? (index.nameByDxrId[uuid] ?: '') : "${kind}#${n}").toString()]
    }
    (src['dxr#tags'] instanceof List ? src['dxr#tags'] : []).each { t -> ref(index.labelDxrIdByIndexId, t.toString(), 'label') }
    def upc = 'annotation_stats#unique_phrase_count.'
    def ext = 'extracted_metadata#'
    src.each { k, v ->
        def key = k.toString()
        if (key.startsWith(upc)) {
            def count = v instanceof Number ? v.intValue() : (v != null && v.toString().isInteger() ? v.toString().toInteger() : 0)
            if (count > 0) { ref(index.annotatorDxrIdByIndexId, key.substring(upc.length()), 'annotator') }
        } else if (key.startsWith(ext)) {
            if (v != null && !v.toString().trim().isEmpty()) { ref(index.extractorDxrIdByIndexId, key.substring(ext.length()), 'extractor') }
        }
    }
    return [id, datasource.toString(), path, fileName, size, modified, '', refs]
}

// Datasource ids in a page that `dsNames` does not know yet.
def unknownDatasourceIds(List hits, Map dsNames) {
    def out = [] as Set
    hits.each { h ->
        def src = h?._source instanceof Map ? h._source : [:]
        def dsId = (src.datasource_id ?: '').toString()
        if (dsId && !(dsNames ?: [:]).containsKey(dsId)) { out << dsId }
    }
    return out as List
}

// Preview rows for renderPreviewHtml(), which expects /api/v1/files-shaped maps.
def tuplesAsPreviewRows(List tuples) {
    return tuples.collect { t -> [datasource: [name: t[1]], path: t[2]] }
}

// --- Criteria → query_items ---------------------------------------------------

// criteria: [labelIndexIds:, labelNames:, extractorIndexIds:, extractorNames:,
//            annotatorIndexIds:, annotatorNames:, filter:,
//            filterAnnotatorIndexIds:, filterAnnotatorNames:]
// allAnnotatorIndexIds: every annotator Collibra knows (for a phrase with no picked annotators).
// Every criterion is its own AND group (files must carry every one); the phrase
// is one group whose items are OR-ed across the annotators it is searched in —
// the same semantics dxr_query.groovy encodes as KQL for the on-prem variant.
// Returns [items: List, unresolved: List<String>] — a criterion without a known
// numeric index id is reported, never silently dropped.
def composeEdgeQueryItems(Map criteria, List allAnnotatorIndexIds) {
    def items = []
    def unresolved = []
    int group = 0
    def nameAt = { List names, int i -> (names != null && i < names.size()) ? names[i] : '?' }
    def present = { Object v -> v != null && !v.toString().trim().isEmpty() }

    (criteria.labelIndexIds ?: []).eachWithIndex { n, i ->
        if (!present(n)) { unresolved << "label '${nameAt(criteria.labelNames, i)}'".toString() }
        else { items << edgeQueryItem('dxr#tags', n, 'number', 'exact', 'AND', group++, 0) }
    }
    (criteria.annotatorIndexIds ?: []).eachWithIndex { n, i ->
        if (!present(n)) { unresolved << "annotator '${nameAt(criteria.annotatorNames, i)}'".toString() }
        else { items << edgeQueryItem('annotators', "annotation.${n}", 'text', 'exists', 'AND', group++, 0) }
    }
    (criteria.extractorIndexIds ?: []).eachWithIndex { n, i ->
        if (!present(n)) { unresolved << "extractor '${nameAt(criteria.extractorNames, i)}'".toString() }
        else { items << edgeQueryItem("extracted_metadata#${n}", '', 'text', 'isSet', 'AND', group++, 0) }
    }
    def phrase = (criteria.filter ?: '').toString().trim()
    if (!phrase.isEmpty()) {
        def targets = []
        def picked = criteria.filterAnnotatorIndexIds ?: []
        if (picked.isEmpty()) {
            // No annotators picked for the text: the phrase may appear in ANY annotator.
            targets.addAll(allAnnotatorIndexIds ?: [])
        } else {
            picked.eachWithIndex { n, i ->
                if (!present(n)) { unresolved << "annotated-text annotator '${nameAt(criteria.filterAnnotatorNames, i)}'".toString() }
                else { targets << n.toString() }
            }
        }
        targets.eachWithIndex { n, i ->
            items << edgeQueryItem("annotation.${n}", phrase, 'text', 'contains', i == 0 ? 'AND' : 'OR', group, i)
        }
        group++
    }
    return [items: items, unresolved: unresolved]
}

def edgeQueryItem(String parameter, Object value, String type, String matchStrategy, String operator, int groupId, int groupOrder) {
    return [parameter: parameter, value: value.toString(), type: type, match_strategy: matchStrategy,
            operator: operator, group_id: groupId, group_order: groupOrder]
}

// The user-facing explanation when a criterion has no numeric index id.
def edgeUnresolvedMessage(String verb, List unresolved) {
    return "Cannot ${verb}: ${unresolved.join(', ')} ${unresolved.size() == 1 ? 'has' : 'have'} no Data X-Ray Index ID in Collibra. Run Sync Data X-Ray Classifications (Collibra Cloud + Edge edition) first, then try again.".toString()
}

// Zero every import counter the page loop advances (the edge equivalent of
// worklist.groovy's publishWorkList — there is no work list, pages are batches).
def zeroEdgeImportCounters(int total, int pageSize) {
    execution.setVariable('importTotal', total)
    execution.setVariable('importBatchCount', (int) Math.ceil(total / (double) Math.max(1, pageSize)))
    execution.setVariable('importCreatedCount', 0)
    execution.setVariable('importUpdatedCount', 0)
    execution.setVariable('importFailedCount', 0)
    execution.setVariable('importRelationCount', 0)
    execution.setVariable('importRelationsRemovedCount', 0)
    execution.setVariable('importSkippedClassifications', 0)
}

// --- The files loop: one page per External API task, plus datasource lookups ---

// Datasource names are fetched one id at a time (GET /api/datasources/<id>,
// api-version V3) the first time a page mentions the id, and cached in the
// dxrDatasourceNames JSON variable. While names are being fetched the page
// body waits in dxrPendingPage.
def armEdgeDatasourceLookup(String dsId) {
    execution.setVariable('dxrStage', 'datasource')
    execution.setVariable('dxrPendingDatasourceId', dsId)
    armEdgeRequest('GET', "/api/datasources/${dsId}".toString(), '', 'api-version: V3')
    edgeResetRetries()
    execution.setVariable('hasMoreWork', true)
}

// opts: label (String, log prefix), dataxrayUrl (String), pageSize (int),
//       index (Map, buildEdgeClassificationIndex result),
//       onFatal (Closure String→void: record the abort; never throws),
//       onPage (Closure (Map page, List tuples) → boolean continueToNextPage),
//       onPageZero (Closure Map page → String veto message or null, optional).
// Reads the armed request's response and drives the stage machine:
//   files → (datasource lookups as needed) → onPage → next page or done.
def handleEdgeFilesPage(Map opts) {
    def stage = (execution.getVariable('dxrStage') ?: 'files').toString()
    def r = readEdgeResponse()
    def dsNames = readJsonVariable('dxrDatasourceNames', [:])

    if (stage == 'datasource') {
        def dsId = (execution.getVariable('dxrPendingDatasourceId') ?: '').toString()
        if (r.ok) {
            def parsed = new groovy.json.JsonSlurper().parseText(r.body)
            dsNames[dsId] = ((parsed instanceof Map ? parsed.name : null) ?: dsId).toString()
        } else {
            if (edgeRetry("Data X-Ray datasource ${dsId} lookup", r.error)) { return }
            loggerApi.warn("Datasource ${dsId} could not be resolved (${r.error}); file assets will show the id")
            dsNames[dsId] = dsId
        }
        edgeResetRetries()
        execution.setVariable('dxrDatasourceNames', groovy.json.JsonOutput.toJson(dsNames))
        def pending = (execution.getVariable('dxrPendingPage') ?: '').toString()
        def page = parseEdgeSearchPage(pending)
        def missing = unknownDatasourceIds(page.hits, dsNames)
        if (!missing.isEmpty()) { armEdgeDatasourceLookup(missing[0]); return }
        execution.removeVariable('dxrPendingPage')
        execution.setVariable('dxrStage', 'files')
        processEdgePage(page, dsNames, opts)
        return
    }

    // stage == 'files'
    int pageNo = (execution.getVariable('dxrPageNo') ?: 0) as int
    def what = "${opts.label} page ${pageNo + 1}".toString()
    if (!r.ok) {
        if (edgeRetry(what, r.error)) { return }
        opts.onFatal(r.error)
        return
    }
    def page
    try {
        page = parseEdgeSearchPage(r.body)
    } catch (Exception parseEx) {
        if (edgeRetry(what, parseEx.message)) { return }
        opts.onFatal("Data X-Ray search response could not be parsed: ${parseEx.message}".toString())
        return
    }
    edgeResetRetries()
    if (pageNo == 0 && opts.onPageZero) {
        def veto = opts.onPageZero(page)
        if (veto) {
            opts.onFatal(veto.toString())
            return
        }
    }
    def missing = unknownDatasourceIds(page.hits, dsNames)
    if (!missing.isEmpty()) {
        execution.setVariable('dxrPendingPage', r.body)
        armEdgeDatasourceLookup(missing[0])
        return
    }
    processEdgePage(page, dsNames, opts)
}

def processEdgePage(Map page, Map dsNames, Map opts) {
    int pageNo = (execution.getVariable('dxrPageNo') ?: 0) as int
    int pageSize = opts.pageSize as int
    def tuples = page.hits.collect { tupleFromSearchHit(it, opts.index, dsNames, opts.dataxrayUrl) }.findAll { it != null }
    boolean wantMore = opts.onPage(page, tuples)
    int nextPage = pageNo + 1
    boolean more = wantMore && !page.hits.isEmpty() && (nextPage * pageSize) < page.total
    if (more) {
        def items = readJsonVariable('dxrQueryItems', [])
        startEdgeFilesPage(items, nextPage, pageSize, page.pitId)
    } else {
        execution.setVariable('hasMoreWork', false)
        execution.setVariable('dxrFetchComplete', true)
    }
}

// The import/rerun page handler: upsert the page as one batch and keep counters.
// opts additionally: queryAssetId (UUID), classIndex (Map byDxrId/byName → asset ids).
def importEdgePage(Map opts, Map page, List tuples) {
    int pageNo = (execution.getVariable('dxrPageNo') ?: 0) as int
    int pageSize = opts.pageSize as int
    if (pageNo == 0) {
        execution.setVariable('importTotal', page.total)
        execution.setVariable('importBatchCount', (int) Math.ceil(page.total / (double) pageSize))
    }
    int batchCount = (execution.getVariable('importBatchCount') ?: 0) as int
    int unresolved = resolveTupleClassifications(tuples, opts.classIndex)
    execution.setVariable('importSkippedClassifications',
        ((execution.getVariable('importSkippedClassifications') ?: 0) as int) + unresolved)
    def res = tuples.isEmpty()
        ? [created: 0, updated: 0, failed: 0, relationsAdded: 0, relationsRemoved: 0]
        : processFileBatch(tuples, opts.queryAssetId, "${opts.label} ${pageNo + 1}/${batchCount}".toString())
    accumulateBatchCounters(res)
    if (opts.afterPage) { opts.afterPage(tuples, pageNo) }
    loggerApi.info("${opts.label} ${pageNo + 1}/${batchCount}: +${res.created} created, +${res.updated} updated, +${res.failed} failed, +${res.relationsAdded}/-${res.relationsRemoved} relation(s); ${Math.min((pageNo + 1) * pageSize, page.total)}/${page.total} done")
    return true
}
