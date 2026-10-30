package fake

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// DataXray as the Cloud + Edge edition sees it through Collibra's External API
// task: POST /api/indexed-files/search (paged, query_items filter) and
// GET /api/datasources/<id>. Any other request fails the test, so a script
// that starts calling a new endpoint is noticed.
//
// Files are maps:
//   [id: 'f1' (the index _id = DataXray file id), ds: 312, dsName: 'SharePoint',
//    objectId: 'Shared Documents/1/7', name: 'a.pdf', folder: '/Shared Documents',
//    size: 27885, modified: '2025-04-10T17:22:00Z',
//    labels: ['7'], annotators: ['12': 3], extractors: ['5': 'NL91 ABNA'],
//    phrases: ['12': ['john smith']], rowBytes: 1200 (optional: simulated row size)]
//
// Collibra's response limit is modelled: a body above `maxResponseBytes` comes
// back as the External API task's error ("Response size exceeds the allowed
// limit.") — or, with `refusalAsEmpty500`, as HTTP 500 with an empty body, the
// form TRC saw on 9 Oct 2026. A row's size is its JSON length unless the file
// sets rowBytes. A request that excludes every field ('*') returns the _id only.
class FakeDataXray {
    List<Map> files = []
    Map<String, String> datasources = [:]          // id → name (filled from files too)
    int maxResponseBytes = 100 * 1024
    boolean refusalAsEmpty500 = false
    int maxResultWindow = 10_000
    List<Map> requests = []                         // [method:, path:, body: (parsed)]
    List<Map> queued = []                           // canned responses, consumed first

    /** The next request gets this response instead: [status:, body:] or [error:]. */
    void failNext(Map response) { queued << response }

    void add(Map file) {
        files << file
        if (file.ds != null && file.dsName) { datasources[file.ds.toString()] = file.dsName.toString() }
    }

    void remove(String fileId) { files.removeAll { it.id == fileId } }

    /** Answer one External API call: [status:, body:, reason:] or [error:]. */
    Map handle(String method, String path, String headers, String body) {
        def parsed = (body ?: '').trim() ? new JsonSlurper().parseText(body) : null
        requests << [method: method, path: path, body: parsed]
        if (!queued.isEmpty()) { return queued.remove(0) }
        if (method == 'POST' && path == '/api/indexed-files/search') { return search((Map) parsed) }
        def ds = (path =~ /^\/api\/datasources\/(\d+)$/)
        if (method == 'GET' && ds.matches()) {
            def name = datasources[ds.group(1)]
            return name == null
                ? [status: 404, body: JsonOutput.toJson([message: "Datasource ${ds.group(1)} not found".toString()])]
                : [status: 200, body: JsonOutput.toJson([id: ds.group(1) as long, name: name])]
        }
        throw new IllegalStateException("FakeDataXray: no handler for ${method} ${path}")
    }

    List<Map> searchRequests() { requests.findAll { it.path == '/api/indexed-files/search' } }

    private Map search(Map req) {
        def items = (req?.filter?.query_items ?: []) as List<Map>
        def matching = files.findAll { matches(it, items) }
            .sort { a, b -> (a.ds as long) <=> (b.ds as long) ?: a.name.toString() <=> b.name.toString() }
        int size = req.pageSize as int
        int from = (req.pageNumber as int) * size
        boolean idsOnly = (req.excludedFields as List)?.contains('*')
        def hits = matching.drop(from).take(size).collect { f -> idsOnly ? [_id: f.id, _source: [:]] : hit(f) }
        def answer = [hits: [total: [value: matching.size(), relation: 'eq'], hits: hits],
                      pit_id: 'fake-pit', max_result_window: maxResultWindow]
        def text = JsonOutput.toJson(answer)
        int bytes = idsOnly ? text.length()
            : hits.size() == 0 ? text.length()
            : matching.drop(from).take(size).sum { f -> (f.rowBytes ?: JsonOutput.toJson(hit(f)).length()) as int } as int
        if (bytes > maxResponseBytes) {
            return refusalAsEmpty500 ? [status: 500, body: '', reason: 'Internal Server Error']
                                     : [error: 'Response size exceeds the allowed limit.']
        }
        return [status: 200, body: text, reason: 'OK']
    }

    static Map hit(Map f) {
        def src = [datasource_id: f.ds as long, object_id: f.objectId, 'ds#file_name': f.name,
                   'ds#parent_folder_paths': f.folder ? [f.folder] : [], 'ds#file_size': f.size,
                   'metadata#MODIFIED_DATE': f.modified, 'dxr#tags': (f.labels ?: []).collect { it as long }]
        (f.annotators ?: [:]).each { n, count -> src["annotation_stats#unique_phrase_count.${n}".toString()] = count }
        (f.extractors ?: [:]).each { n, value -> src["extracted_metadata#${n}".toString()] = value }
        return [_id: f.id, _source: src]
    }

    // Groups are AND-ed; inside a group each item joins the running result with
    // its own operator (the first item's operator links the group to the rest).
    static boolean matches(Map f, List<Map> items) {
        items.groupBy { it.group_id }.every { gid, group ->
            boolean result = false
            group.sort { it.group_order }.eachWithIndex { item, i ->
                boolean m = itemMatches(f, item)
                result = i == 0 ? m : (item.operator == 'OR' ? (result || m) : (result && m))
            }
            result
        }
    }

    static boolean itemMatches(Map f, Map item) {
        String p = item.parameter
        String v = item.value?.toString()
        switch (item.match_strategy) {
            case 'exact':
                if (p == 'datasource_id') { return f.ds.toString() == v }
                if (p == 'object_id') { return f.objectId?.toString() == v }
                if (p == 'dxr#tags') { return (f.labels ?: []).collect { it.toString() }.contains(v) }
                break
            case 'exists':
                if (p == 'annotators' && v.startsWith('annotation.')) {
                    return ((f.annotators ?: [:])[v - 'annotation.'] ?: 0) as int > 0
                }
                break
            case 'isSet':
                if (p.startsWith('extracted_metadata#')) {
                    def value = (f.extractors ?: [:])[p - 'extracted_metadata#']
                    return value != null && !value.toString().trim().isEmpty()
                }
                break
            case 'contains':
                if (p.startsWith('annotation.')) {
                    def phrases = ((f.phrases ?: [:])[p - 'annotation.'] ?: []) as List
                    return phrases.any { it.toString().toLowerCase().contains(v.toLowerCase()) }
                }
                break
        }
        throw new IllegalStateException("FakeDataXray: query item not modelled: ${item}")
    }
}
