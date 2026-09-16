// dxr_edge.groovy — the Collibra Cloud + Edge transport to Data X-Ray.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// EDGE VARIANT ONLY. No HTTP is opened from Groovy: every call is a Collibra
// **External API task** (BPMN serviceTask flowable:type="http") routed through
// an Edge site's HTTP connection, which also holds the credentials. A script
// before the task "arms" the request in process variables; the task runs
// asynchronously; the script after it reads the response variables.
//
// Facts verified on ohalo.collibra.com (Collibra Cloud 2026.09, Edge 2026.6),
// September 2026:
//   - <design:http-connection-name>${dataxrayConnectionName}</…> resolves an
//     expression to a connection on the Edge site; Basic auth on the connection
//     authenticates to Data X-Ray.
//   - requestUrl is a RELATIVE path appended to the connection's Host; method,
//     headers, path and body all accept expressions.
//   - The response body lands in <responseVariableName> as a String; with
//     saveResponseParameters, <prefix>ResponseStatusCode (Integer),
//     <prefix>ResponseReason and <prefix>ResponseHeaders are set too.
//   - Every HTTP status is "success": a 404 simply yields status 404.
//   - Responses above ~512 KB fail with <prefix>ErrorMessage
//     "Response size exceeds the allowed limit." and, with ignoreException=true,
//     the workflow CONTINUES (no dead-letter) with no body/status variables.
//   - One round trip costs ~0.6–2 s.
//
// Data X-Ray side: the pack uses DXR's internal search API,
// POST /api/indexed-files/search, because the public /api/v1/files endpoint
// streams the whole index and cannot be paged. Contract verified against
// demo.dataxray.io (Sept 2026) — see the pack CLAUDE.md "Edge variant" section.

// {{include:dxr_rows.groovy}}
// {{include:dxr_query.groovy}}

// --- Arming requests and reading responses ------------------------------------

// Prime the next External API task: method, relative path, body, headers.
def armEdgeRequest(String method, String path, String body) {
    execution.setVariable('dxrMethod', method)
    execution.setVariable('dxrRequestPath', path)
    execution.setVariable('dxrRequestBody', body ?: '')
    execution.setVariable('dxrRequestHeaders', method == 'GET'
        ? 'Accept: application/json'
        : 'Content-Type: application/json\nAccept: application/json')
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

// --- The Data X-Ray catalogues (numeric index ids → the UUIDs Collibra stores) --

// The index stores labels as numeric tag ids (dxr#tags), annotator hits under
// annotation_stats#unique_phrase_count.<numericId> and extractor output under
// extracted_metadata#<numericId>, while /api/v1/classifications — and therefore
// every Collibra classification asset's "Data X-Ray ID" attribute — uses UUIDs.
// These four small internal endpoints bridge the two, and name datasources.
def edgeCatalogueRequests() {
    return [
        [stage: 'tags',        path: '/api/tags?page=0&size=1000'],
        [stage: 'dataClasses', path: '/api/data-classes'],
        [stage: 'extractors',  path: '/api/metadata-extractors?page=0&size=1000'],
        [stage: 'datasources', path: '/api/datasources/browse'],
    ]
}

def emptyEdgeCatalogue() {
    return [tagUuidById: [:], tagNameById: [:], annotatorUuidById: [:], annotatorNameById: [:],
            extractorUuidById: [:], extractorNameById: [:], datasourceNameById: [:]]
}

def mergeEdgeCatalogue(Map cat, String stage, String body) {
    def parsed = new groovy.json.JsonSlurper().parseText(body)
    def asList = { v -> v instanceof List ? v : (v instanceof Map && v.content instanceof List ? v.content : []) }
    switch (stage) {
        case 'tags':
            asList(parsed).each { t ->
                if (t instanceof Map && t.id != null && t.uuid) {
                    cat.tagUuidById[t.id.toString()] = t.uuid.toString()
                    cat.tagNameById[t.id.toString()] = (t.name ?: '').toString()
                }
            }
            break
        case 'dataClasses':
            // { dictionaries: [...], readOnlyDictionaries: [...], regexes: [...], readOnlyRegexes: [...],
            //   namedEntities: [...], readOnlyNamedEntities: [...] }
            (parsed instanceof Map ? parsed.values() : []).each { group ->
                asList(group).each { a ->
                    if (a instanceof Map && a.id != null && a.uuid) {
                        cat.annotatorUuidById[a.id.toString()] = a.uuid.toString()
                        cat.annotatorNameById[a.id.toString()] = (a.name ?: '').toString()
                    }
                }
            }
            break
        case 'extractors':
            asList(parsed).each { e ->
                if (e instanceof Map && e.id != null && e.uuid) {
                    cat.extractorUuidById[e.id.toString()] = e.uuid.toString()
                    cat.extractorNameById[e.id.toString()] = (e.name ?: '').toString()
                }
            }
            break
        case 'datasources':
            asList(parsed).each { d ->
                if (d instanceof Map && d.datasourceId != null) {
                    cat.datasourceNameById[d.datasourceId.toString()] = (d.datasourceName ?: '').toString()
                }
            }
            break
        default:
            throw new IllegalStateException("Unknown catalogue stage '${stage}'")
    }
    return cat
}

def invertStringMap(Map m) {
    def out = [:]
    m.each { k, v -> out[v.toString()] = k.toString() }
    return out
}

// Begin the catalogue phase: arm the first catalogue request.
def startEdgeCatalogue() {
    execution.setVariable('dxrCatalogue', groovy.json.JsonOutput.toJson(emptyEdgeCatalogue()))
    execution.setVariable('dxrStage', 'catalogue')
    execution.setVariable('dxrCatalogueIndex', 0)
    armEdgeRequest('GET', edgeCatalogueRequests()[0].path, '')
    edgeResetRetries()
    execution.setVariable('hasMoreWork', true)
}

// Fold one catalogue response in and arm the next request. Returns true once
// the last catalogue response has been merged (nothing is armed then).
def advanceEdgeCatalogue(String body) {
    def requests = edgeCatalogueRequests()
    int idx = (execution.getVariable('dxrCatalogueIndex') ?: 0) as int
    def cat = mergeEdgeCatalogue(loadEdgeCatalogue(), requests[idx].stage, body)
    execution.setVariable('dxrCatalogue', groovy.json.JsonOutput.toJson(cat))
    idx++
    execution.setVariable('dxrCatalogueIndex', idx)
    if (idx < requests.size()) {
        armEdgeRequest('GET', requests[idx].path, '')
        execution.setVariable('hasMoreWork', true)
        return false
    }
    loggerApi.info("Data X-Ray catalogue loaded: ${cat.tagUuidById.size()} label(s), ${cat.annotatorUuidById.size()} annotator(s), ${cat.extractorUuidById.size()} extractor(s), ${cat.datasourceNameById.size()} datasource(s)")
    return true
}

def loadEdgeCatalogue() {
    def raw = (execution.getVariable('dxrCatalogue') ?: '').toString()
    return raw.isEmpty() ? emptyEdgeCatalogue() : new groovy.json.JsonSlurper().parseText(raw)
}

// --- The search request -------------------------------------------------------

// Fields dropped from every hit to keep a 50-row page well under the response
// cap (~60–80 KB measured). annotation_stats#unique_phrase_count.<id> is kept
// as the per-annotator hit evidence; annotation.<id> (the phrases) is not needed.
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

// The page size configuration variable, clamped to a sane range.
def edgePageSize(Object raw, int fallback) {
    def s = raw == null ? '' : raw.toString().trim()
    if (!s.isInteger()) { return fallback }
    return Math.max(1, Math.min(200, s.toInteger()))
}

// --- Rows ----------------------------------------------------------------------

// Distill one search hit into the same work-item tuple dxr_rows.groovy defines
// for /api/v1/files rows, using the catalogue to translate numeric ids.
// Classification refs are [uuid, name] pairs; a numeric id the catalogue does
// not know becomes ['', 'kind#<id>'] so it counts as unresolved downstream.
def tupleFromSearchHit(Map hit, Map cat, String baseUrl) {
    def src = hit?._source instanceof Map ? hit._source : [:]
    def id = (hit?._id ?: '').toString()
    if (id.isEmpty()) { return null }

    def fileName = (src['ds#file_name'] ?: '').toString().replaceFirst('^/', '')
    def folders = src['ds#parent_folder_paths']
    def folder = (folders instanceof List && !folders.isEmpty()) ? (folders[0] ?: '').toString().replaceAll('/+$', '') : ''
    def path = folder.isEmpty() ? fileName : "${folder}/${fileName}".toString()
    def dsId = (src.datasource_id ?: '').toString()
    def datasource = cat.datasourceNameById[dsId] ?: dsId
    def size = src['ds#file_size'] == null ? '' : src['ds#file_size'].toString()
    def modified = (src['metadata#MODIFIED_DATE'] ?: '').toString()

    def refs = []
    (src['dxr#tags'] instanceof List ? src['dxr#tags'] : []).each { t ->
        def n = t.toString()
        refs << [(cat.tagUuidById[n] ?: '').toString(), (cat.tagNameById[n] ?: "label#${n}").toString()]
    }
    def upc = 'annotation_stats#unique_phrase_count.'
    def ext = 'extracted_metadata#'
    src.each { k, v ->
        def key = k.toString()
        if (key.startsWith(upc)) {
            def n = key.substring(upc.length())
            def count = v instanceof Number ? v.intValue() : (v != null && v.toString().isInteger() ? v.toString().toInteger() : 0)
            if (count > 0) {
                refs << [(cat.annotatorUuidById[n] ?: '').toString(), (cat.annotatorNameById[n] ?: "annotator#${n}").toString()]
            }
        } else if (key.startsWith(ext)) {
            def n = key.substring(ext.length())
            if (v != null && !v.toString().trim().isEmpty()) {
                refs << [(cat.extractorUuidById[n] ?: '').toString(), (cat.extractorNameById[n] ?: "extractor#${n}").toString()]
            }
        }
    }
    return [id, datasource.toString(), path, fileName, size, modified, '', refs]
}

// Preview rows for renderPreviewHtml(), which expects /api/v1/files-shaped maps.
def tuplesAsPreviewRows(List tuples) {
    return tuples.collect { t -> [datasource: [name: t[1]], path: t[2]] }
}

// --- Criteria → query_items ---------------------------------------------------

// criteria: [labelDxrIds:, labelNames:, extractorDxrIds:, extractorNames:,
//            annotatorDxrIds:, annotatorNames:, filter:, filterAnnotatorDxrIds:, filterAnnotatorNames:]
// Every criterion is its own AND group (files must carry every one); the phrase
// is one group whose items are OR-ed across the annotators it is searched in —
// the same semantics dxr_query.groovy encodes as KQL for the on-prem variant.
// Returns [items: List, unresolved: List<String>] — a criterion whose UUID the
// Data X-Ray catalogue does not know is reported, never silently dropped.
def composeEdgeQueryItems(Map criteria, Map cat) {
    def tagIdByUuid       = invertStringMap(cat.tagUuidById)
    def annotatorIdByUuid = invertStringMap(cat.annotatorUuidById)
    def extractorIdByUuid = invertStringMap(cat.extractorUuidById)
    def items = []
    def unresolved = []
    int group = 0

    def nameAt = { List names, int i -> (names != null && i < names.size()) ? names[i] : '?' }

    (criteria.labelDxrIds ?: []).eachWithIndex { u, i ->
        def n = tagIdByUuid[(u ?: '').toString()]
        if (!n) { unresolved << "label '${nameAt(criteria.labelNames, i)}'".toString() }
        else { items << edgeQueryItem('dxr#tags', n, 'number', 'exact', 'AND', group++, 0) }
    }
    (criteria.annotatorDxrIds ?: []).eachWithIndex { u, i ->
        def n = annotatorIdByUuid[(u ?: '').toString()]
        if (!n) { unresolved << "annotator '${nameAt(criteria.annotatorNames, i)}'".toString() }
        else { items << edgeQueryItem('annotators', "annotation.${n}", 'text', 'exists', 'AND', group++, 0) }
    }
    (criteria.extractorDxrIds ?: []).eachWithIndex { u, i ->
        def n = extractorIdByUuid[(u ?: '').toString()]
        if (!n) { unresolved << "extractor '${nameAt(criteria.extractorNames, i)}'".toString() }
        else { items << edgeQueryItem("extracted_metadata#${n}", '', 'text', 'isSet', 'AND', group++, 0) }
    }
    def phrase = (criteria.filter ?: '').toString().trim()
    if (!phrase.isEmpty()) {
        def targets = []
        def picked = criteria.filterAnnotatorDxrIds ?: []
        if (picked.isEmpty()) {
            // No annotators picked for the text: the phrase may appear in ANY annotator.
            targets.addAll(cat.annotatorUuidById.keySet())
        } else {
            picked.eachWithIndex { u, i ->
                def n = annotatorIdByUuid[(u ?: '').toString()]
                if (!n) { unresolved << "annotated-text annotator '${nameAt(criteria.filterAnnotatorNames, i)}'".toString() }
                else { targets << n }
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

// --- One page of the files phase, shared by the import and the rerun ------------

// opts: label (String, log prefix), queryAssetId (UUID), dataxrayUrl (String),
//       pageSize (int), classIndex (Map byDxrId/byName → asset id strings),
//       onFatal (Closure String→void: record the abort; never throws),
//       onPageZero (Closure Map page → String veto message or null),
//       afterPage (Closure (List tuples, int pageNo) → void, optional).
// Reads the armed page's response, upserts the page as one batch, and either
// arms the next page or marks the fetch complete.
def handleEdgeFilesPage(Map opts) {
    def r = readEdgeResponse()
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

    int pageSize = opts.pageSize as int
    if (pageNo == 0) {
        def veto = opts.onPageZero(page)
        if (veto) {
            opts.onFatal(veto.toString())
            return
        }
        execution.setVariable('importTotal', page.total)
        execution.setVariable('importBatchCount', (int) Math.ceil(page.total / (double) pageSize))
    }
    int batchCount = (execution.getVariable('importBatchCount') ?: 0) as int

    def cat = loadEdgeCatalogue()
    def tuples = page.hits.collect { tupleFromSearchHit(it, cat, opts.dataxrayUrl) }.findAll { it != null }
    int unresolved = resolveTupleClassifications(tuples, opts.classIndex)
    execution.setVariable('importSkippedClassifications',
        ((execution.getVariable('importSkippedClassifications') ?: 0) as int) + unresolved)

    def res = tuples.isEmpty()
        ? [created: 0, updated: 0, failed: 0, relationsAdded: 0, relationsRemoved: 0]
        : processFileBatch(tuples, opts.queryAssetId, "${opts.label} ${pageNo + 1}/${batchCount}".toString())
    accumulateBatchCounters(res)
    if (opts.afterPage) { opts.afterPage(tuples, pageNo) }

    int nextPage = pageNo + 1
    boolean more = !page.hits.isEmpty() && (nextPage * pageSize) < page.total
    loggerApi.info("${opts.label} ${pageNo + 1}/${batchCount}: +${res.created} created, +${res.updated} updated, +${res.failed} failed, +${res.relationsAdded}/-${res.relationsRemoved} relation(s); ${Math.min(nextPage * pageSize, page.total)}/${page.total} done")
    if (more) {
        def items = new groovy.json.JsonSlurper().parseText((execution.getVariable('dxrQueryItems') ?: '[]').toString())
        startEdgeFilesPage(items, nextPage, pageSize, page.pitId)
    } else {
        execution.setVariable('hasMoreWork', false)
        execution.setVariable('dxrFetchComplete', true)
    }
}
