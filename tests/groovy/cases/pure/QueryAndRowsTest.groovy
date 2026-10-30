// How a saved search becomes a DataXray query, and how DataXray rows become
// work items. TRC story 9 (AND semantics); EVID-1..6; FILE-1.

// --- on-prem: KQL for /api/v1/files -----------------------------------------------------------

test('every criterion is AND-ed; the phrase is OR-ed only across its annotators (story 9)') {
    def q = composeDxrQuery(['Contacts'], ['IBAN'], ['Email', 'Phone'], 'smith', ['Email', 'Name'])
    assert q == 'labels.name:"Contacts" AND extractedMetadata.name:"IBAN" AND annotators.name:"Email" AND ' +
        'annotators.name:"Phone" AND annotators: { (name:"Email" OR name:"Name") AND annotations.phrase:"*smith*" }'
}

test('a phrase without picked annotators is searched in all of them') {
    assert composeDxrQuery([], [], [], 'smith', []) == 'annotators: { annotations.phrase:"*smith*" }'
}

test('quotes and backslashes in names are escaped (DataXray answers 400 otherwise)') {
    assert quoteTerm('labels.name', 'He said "hi" \\o/') == 'labels.name:"He said \\"hi\\" \\\\o/"'
}

test('no criteria at all is the empty query, shown as (all files) — the rerun refuses it (FILE-4)') {
    assert composeDxrQuery([], [], [], '', []) == ''
    assert displayQuery('') == '(all files)'
}

// --- Cloud + Edge: query_items for the internal search API ----------------------------------------

test('Edge query items: one AND group per criterion, the phrase OR-ed across its annotators') {
    def r = composeEdgeQueryItems([labelIndexIds: ['7'], labelNames: ['Contacts'],
                                   annotatorIndexIds: ['12'], annotatorNames: ['Email'],
                                   extractorIndexIds: ['5'], extractorNames: ['IBAN'],
                                   filter: 'smith', filterAnnotatorIndexIds: ['12', '13'], filterAnnotatorNames: ['Email', 'Name']],
                                  ['12', '13', '14'])
    assert r.unresolved == []
    assert r.items.collect { [it.parameter, it.match_strategy, it.operator, it.group_id] } == [
        ['dxr#tags', 'exact', 'AND', 0],
        ['annotators', 'exists', 'AND', 1],
        ['extracted_metadata#5', 'isSet', 'AND', 2],
        ['annotation.12', 'contains', 'AND', 3],
        ['annotation.13', 'contains', 'OR', 3],
    ]
}

test('a criterion with no DataXray Index ID is reported, never dropped (it would broaden the search)') {
    def r = composeEdgeQueryItems([labelIndexIds: ['7', ''], labelNames: ['Contacts', 'Unsynced']], [])
    assert r.unresolved == ["label 'Unsynced'"]
    assert edgeUnresolvedMessage('rerun', r.unresolved).contains('Run Sync DataXray Classifications')
}

// --- rows → tuples ------------------------------------------------------------------------------

test('/api/v1/files rows: only classifications with hit evidence count (EVID-1, -3, -4, -5)') {
    vars.dxrExtractedValuesOn = 'false'
    def t = extractFileTuple([fileId: 'f1', datasource: [name: 'Share'], path: '/a/b.pdf', size: 10,
                              lastModifiedAt: '2025-04-10T17:22:00Z',
                              labels: [[id: 'l1', name: 'Contacts']],
                              extractedMetadata: [[id: 'e1', name: 'IBAN', value: 'NL91']],
                              annotators: [[id: 'a1', name: 'Email', uniquePhrases: 2],
                                           [id: 'a2', name: 'Phone', uniquePhrases: 0],
                                           [id: 'a3', name: 'Name', annotations: []]]], 'https://dxr')
    assert t[0..6] == ['f1', 'Share', '/a/b.pdf', 'b.pdf', '10', '2025-04-10T17:22:00Z', '']
    assert t[7] == [['l1', 'Contacts'], ['e1', 'IBAN'], ['a1', 'Email']]
    assert t[8] == '' && t[9] == ''                                      // on-prem rows carry no object key
}

test('a row without a file id is skipped') {
    vars.dxrExtractedValuesOn = 'false'
    assert extractFileTuple([path: '/a'], '') == null
}

test('search hits (Edge): evidence by numeric id, object key, folder + name as path (EVID-3, -4)') {
    vars.dxrExtractedValuesOn = 'false'
    def index = [labelDxrIdByIndexId: ['7': 'uuid-l'], annotatorDxrIdByIndexId: ['12': 'uuid-a', '13': 'uuid-z'],
                 extractorDxrIdByIndexId: [:], nameByDxrId: ['uuid-l': 'Contacts', 'uuid-a': 'Email', 'uuid-z': 'Zero']]
    def hit = [_id: 'f1', _source: [datasource_id: 312, object_id: 'Shared/1/7', 'ds#file_name': '/b.pdf',
                                    'ds#parent_folder_paths': ['/Shared Documents/'], 'ds#file_size': 10,
                                    'dxr#tags': [7, 99],
                                    'annotation_stats#unique_phrase_count.12': 3,
                                    'annotation_stats#unique_phrase_count.13': 0]]
    def t = tupleFromSearchHit(hit, index, ['312': 'SharePoint'], '')
    assert t[0..3] == ['f1', 'SharePoint', '/Shared Documents/b.pdf', 'b.pdf']
    assert t[8] == '312' && t[9] == 'Shared/1/7'
    assert t[7] as Set == [['uuid-l', 'Contacts'], ['', 'label#99'], ['uuid-a', 'Email']] as Set
}

test('resolveTupleClassifications: by DataXray ID first, then by name; the rest are counted') {
    def items = [['f1', '', '', '', '', '', '', [['id-1', 'X'], ['', 'ByName'], ['', 'label#99']]]]
    def unresolved = resolveTupleClassifications(items, [byDxrId: ['id-1': 'asset-1'], byName: ['ByName': 'asset-2']])
    assert unresolved == 1
    assert items[0][7] as Set == ['asset-1', 'asset-2'] as Set
}

// --- Edge paging arithmetic ----------------------------------------------------------------------

table('largestPageSizeFor: a smaller page must start exactly at the current row', [
    [case: 'row 0', offset: 0, max: 12, out: 12],
    [case: 'row 25, at most 12', offset: 25, max: 12, out: 5],
    [case: 'row 24, at most 12', offset: 24, max: 12, out: 12],
    [case: 'a prime row', offset: 23, max: 12, out: 1],
]) { row -> assert largestPageSizeFor(row.offset, row.max) == row.out }

table('isEdgeRefusal: what counts as Collibra refusing a response as too large', [
    [case: 'the explicit error', r: [error: 'Edge request failed: Response size exceeds the allowed limit.', status: -1, body: ''], out: true],
    [case: '500 with an empty body (TRC)', r: [error: 'x', status: 500, body: ''], out: true],
    [case: '500 with a JSON body is a real DataXray error', r: [error: 'x', status: 500, body: '{"error":"boom"}'], out: false],
    [case: '404', r: [error: 'x', status: 404, body: ''], out: false],
]) { row -> assert isEdgeRefusal(row.r) == row.out }
