// dxr_edge_sync.groovy — fetching the Data X-Ray classification catalogue
// through Edge without ever exceeding Collibra's 100 KB response cap.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// EDGE VARIANT ONLY.
//
// The public GET /api/v1/classifications returns the whole catalogue in one
// body (~300 bytes per item — a tenant with ~900 classifications answers ~300 KB
// and the External API task refuses it). Data X-Ray has no paged catalogue
// endpoint, so the Edge sync assembles the same items from smaller calls:
//
//   GET /api/tags                                labels (all; ~450 B each)
//   GET /api/metadata-extractors                 extractors (all; usually few)
//   GET /api/data-classes                        every annotator with full detail (~470 B each) —
//                                                complete, but refused by Collibra above ~200 annotators
//   fallback when that is refused as oversized:
//   GET /api/datasources/ingester/index/classes  annotators that currently have FINDINGS in the index
//                                                (compact: numeric id + name; NOT the whole catalogue)
//   GET /api/data-classes/<id>                   ONE annotator's detail, fetched only for annotators
//                                                Collibra does not know yet or whose name changed
//
// In fallback mode the annotator list is incomplete (an annotator without hits
// is invisible), so annotators are then NEVER retired by the Edge sync — only
// labels and extractors, whose lists are always complete. Annotators already
// known to Collibra are synced as *partial* items: presence, name and ids are
// authoritative, their description/subtype/link attributes are left as they were.
// The first fallback run costs one round trip per unknown annotator (~1 s each);
// later runs cost a handful of requests.
//
// Item shape handed to syncClassificationCatalog() mirrors /api/v1/classifications:
//   [id: <uuid>, name:, type: LABEL|ANNOTATOR|EXTRACTOR, subtype:, description:,
//    link: '/resource/<uuid>?type=<TYPE>', searchLink: '/resource-search/<uuid>?type=<TYPE>' (LABEL, ANNOTATOR),
//    indexId: <numeric id>, partial: boolean]
// ANNOTATOR_DOMAIN items (data categories) are not produced: the sync never
// mapped them to an asset type (they were skipped as "unknown type").

// {{include:dxr_edge.groovy}}

def edgeSyncListRequests() {
    return [
        [stage: 'tags',           path: '/api/tags'],
        [stage: 'extractors',     path: '/api/metadata-extractors'],
        [stage: 'dataClasses',    path: '/api/data-classes'],
        [stage: 'annotatorIndex', path: '/api/datasources/ingester/index/classes'],   // fallback only
    ]
}

// Begin: arm the first list request. `known` is buildEdgeClassificationIndex()'s
// result (kept in the classIndex variable by the caller).
def startEdgeSync() {
    execution.setVariable('dxrSyncLists', groovy.json.JsonOutput.toJson([tags: [], extractors: [], annotators: []]))
    execution.setVariable('dxrSyncDetails', '{}')
    execution.setVariable('dxrSyncAnnotatorsComplete', false)
    execution.setVariable('dxrSyncQueue', '[]')
    execution.setVariable('dxrSyncFetched', 0)
    execution.setVariable('dxrStage', 'catalogue')
    execution.setVariable('dxrCatalogueIndex', 0)
    armEdgeRequest('GET', edgeSyncListRequests()[0].path, '')
    edgeResetRetries()
    execution.setVariable('hasMoreWork', true)
}

// Handle one response of the sync loop. `known` = classification index from
// Collibra (annotatorDxrIdByIndexId, nameByDxrId). Calls onFatal(msg) and ends
// the loop on an unrecoverable failure. Sets hasMoreWork=false when the
// catalogue is complete (the caller's next task then applies it).
def handleEdgeSyncPage(Map known, Closure onFatal) {
    def stage = (execution.getVariable('dxrStage') ?: '').toString()
    def r = readEdgeResponse()
    def what = stage == 'annotatorDetail'
        ? "Data X-Ray annotator ${execution.getVariable('dxrSyncCurrentId')} detail".toString()
        : "Data X-Ray ${stage} request".toString()
    def requests = edgeSyncListRequests()
    int idx = (execution.getVariable('dxrCatalogueIndex') ?: 0) as int
    if (!r.ok) {
        if (stage == 'catalogue' && requests[idx].stage == 'dataClasses' && r.error.contains('exceeds the allowed limit')) {
            // The complete annotator list does not fit under Collibra's response
            // cap: fall back to the annotators-with-findings list + per-item detail,
            // and remember that annotators must not be retired this run.
            loggerApi.warn("Data X-Ray's full annotator list exceeds Collibra's External API response limit — falling back to annotators with findings; annotators will not be retired by this sync")
            execution.setVariable('dxrSyncAnnotatorsComplete', false)
            edgeResetRetries()
            execution.setVariable('dxrCatalogueIndex', idx + 1)
            armEdgeRequest('GET', requests[idx + 1].path, '')
            execution.setVariable('hasMoreWork', true)
            return
        }
        if (edgeRetry(what, r.error)) { return }
        onFatal("${r.error}. Check the Edge HTTP connection '${execution.getVariable('dataxrayConnectionName')}'.".toString())
        return
    }
    edgeResetRetries()
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(r.body)
    } catch (Exception parseEx) {
        onFatal("Data X-Ray ${stage} response could not be parsed: ${parseEx.message}".toString())
        return
    }

    if (stage == 'catalogue') {
        def lists = readJsonVariable('dxrSyncLists', [tags: [], extractors: [], annotators: []])
        def asList = { v -> v instanceof List ? v : (v instanceof Map && v.content instanceof List ? v.content : []) }
        switch (requests[idx].stage) {
            case 'tags':
                lists.tags = asList(parsed).findAll { it instanceof Map && it.id != null && it.uuid }.collect { t ->
                    [id: t.id.toString(), uuid: t.uuid.toString(), name: (t.name ?: '').toString(),
                     description: t.description, subtype: (t.type ?: '').toString()]
                }
                break
            case 'extractors':
                lists.extractors = asList(parsed).findAll { it instanceof Map && it.id != null && it.uuid }.collect { e ->
                    [id: e.id.toString(), uuid: e.uuid.toString(), name: (e.name ?: '').toString(), description: e.description]
                }
                break
            case 'dataClasses':
                // { dictionaries: [...], readOnlyDictionaries: [...], regexes: [...], readOnlyRegexes: [...],
                //   namedEntities: [...], readOnlyNamedEntities: [...] } — the COMPLETE annotator catalogue.
                def full = []
                (parsed instanceof Map ? parsed.values() : []).each { group ->
                    asList(group).findAll { it instanceof Map && it.id != null && it.uuid }.each { a ->
                        full << [id: a.id.toString(), uuid: a.uuid.toString(), name: (a.name ?: '').toString(),
                                 subtype: (a.type ?: '').toString(), description: a.description]
                    }
                }
                lists.annotators = full
                execution.setVariable('dxrSyncAnnotatorsComplete', true)
                execution.setVariable('dxrSyncLists', groovy.json.JsonOutput.toJson(lists))
                loggerApi.info("Data X-Ray catalogue lists loaded: ${lists.tags.size()} label(s), ${lists.extractors.size()} extractor(s), ${full.size()} annotator(s) (complete)")
                execution.setVariable('dxrStage', 'complete')
                execution.setVariable('hasMoreWork', false)
                return
            case 'annotatorIndex':
                lists.annotators = asList(parsed).findAll { it instanceof Map && it.id != null }.collect { a ->
                    [id: a.id.toString(), name: (a.name ?: '').toString()]
                }
                break
        }
        execution.setVariable('dxrSyncLists', groovy.json.JsonOutput.toJson(lists))
        idx++
        execution.setVariable('dxrCatalogueIndex', idx)
        if (idx < requests.size()) {
            armEdgeRequest('GET', requests[idx].path, '')
            execution.setVariable('hasMoreWork', true)
            return
        }
        // Fallback lists complete: which annotators need their detail fetched?
        def queue = []
        lists.annotators.each { a ->
            def uuid = (known.annotatorDxrIdByIndexId ?: [:])[a.id]
            def knownName = uuid ? (known.nameByDxrId ?: [:])[uuid] : null
            if (!uuid || knownName != a.name) { queue << a.id }
        }
        loggerApi.info("Data X-Ray catalogue lists loaded (fallback): ${lists.tags.size()} label(s), ${lists.extractors.size()} extractor(s), ${lists.annotators.size()} annotator(s) with findings; ${queue.size()} annotator detail(s) to fetch")
        execution.setVariable('dxrSyncQueue', groovy.json.JsonOutput.toJson(queue))
        return armNextEdgeSyncDetail()
    }

    // stage == 'annotatorDetail'
    def currentId = (execution.getVariable('dxrSyncCurrentId') ?: '').toString()
    def details = readJsonVariable('dxrSyncDetails', [:])
    if (parsed instanceof Map && parsed.uuid) {
        details[currentId] = [uuid: parsed.uuid.toString(), name: (parsed.name ?: '').toString(),
                              subtype: (parsed.type ?: '').toString(), description: parsed.description]
    } else {
        loggerApi.warn("Annotator ${currentId}: unexpected detail response (no uuid) — it will be reported as unresolved")
    }
    execution.setVariable('dxrSyncDetails', groovy.json.JsonOutput.toJson(details))
    execution.setVariable('dxrSyncFetched', ((execution.getVariable('dxrSyncFetched') ?: 0) as int) + 1)
    armNextEdgeSyncDetail()
}

// Arm the next annotator detail request, or finish the loop.
def armNextEdgeSyncDetail() {
    def queue = readJsonVariable('dxrSyncQueue', [])
    if (queue.isEmpty()) {
        execution.setVariable('dxrStage', 'complete')
        execution.setVariable('hasMoreWork', false)
        return
    }
    def next = queue.remove(0).toString()
    execution.setVariable('dxrSyncQueue', groovy.json.JsonOutput.toJson(queue))
    execution.setVariable('dxrSyncCurrentId', next)
    execution.setVariable('dxrStage', 'annotatorDetail')
    armEdgeRequest('GET', "/api/data-classes/${next}".toString(), '')
    execution.setVariable('hasMoreWork', true)
}

// Whether this run saw the COMPLETE annotator catalogue (false ⇒ annotators must not be retired).
def edgeSyncAnnotatorsComplete() {
    return execution.getVariable('dxrSyncAnnotatorsComplete') == true
}

// Assemble the /api/v1/classifications-shaped catalogue from the fetched lists,
// the fetched annotator details and what Collibra already knew.
def buildEdgeSyncCatalogue(Map known) {
    def lists = readJsonVariable('dxrSyncLists', [tags: [], extractors: [], annotators: []])
    def details = readJsonVariable('dxrSyncDetails', [:])
    def items = []
    lists.tags.each { t ->
        items << [id: t.uuid, name: t.name, type: 'LABEL', subtype: t.subtype ?: null, description: t.description,
                  link: "/resource/${t.uuid}?type=LABEL".toString(), searchLink: "/resource-search/${t.uuid}?type=LABEL".toString(),
                  indexId: t.id, partial: false]
    }
    lists.extractors.each { e ->
        items << [id: e.uuid, name: e.name, type: 'EXTRACTOR', subtype: 'NONE', description: e.description,
                  link: "/resource/${e.uuid}?type=EXTRACTOR".toString(), searchLink: null,
                  indexId: e.id, partial: false]
    }
    int partial = 0
    int dropped = 0
    lists.annotators.each { a ->
        def d = a.uuid ? a : details[a.id]   // complete list entries carry their detail already
        if (d) {
            items << [id: d.uuid, name: d.name ?: a.name, type: 'ANNOTATOR', subtype: d.subtype ?: null, description: d.description,
                      link: "/resource/${d.uuid}?type=ANNOTATOR".toString(), searchLink: "/resource-search/${d.uuid}?type=ANNOTATOR".toString(),
                      indexId: a.id, partial: false]
            return
        }
        def uuid = (known.annotatorDxrIdByIndexId ?: [:])[a.id]
        if (uuid) {
            items << [id: uuid, name: a.name, type: 'ANNOTATOR', indexId: a.id, partial: true]
            partial++
        } else {
            dropped++
        }
    }
    if (partial > 0 || dropped > 0) {
        loggerApi.info("Catalogue assembled: ${items.size()} item(s), ${partial} annotator(s) synced from Collibra's cache, ${dropped} without detail")
    }
    return items
}
