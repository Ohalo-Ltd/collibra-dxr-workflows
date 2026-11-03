// The "Extracted value" search criterion (v2.12): parsing, the on-prem KQL
// clause and the Cloud + Edge query items. VAL-1..6. The DataXray semantics
// these encode were checked on a live DataXray (demo, 3 Nov 2026).

// --- parsing --------------------------------------------------------------------------------

table('the extracted value: * only at the ends; true/false and numbers also match typed extractors (VAL-2)', [
    [case: 'Yes', in: 'Yes',        strategy: 'exact',      core: 'Yes',     literal: 'text'],
    [case: '  *invoice* ', in: '  *invoice* ', strategy: 'contains', core: 'invoice', literal: 'text'],
    [case: 'inv*', in: 'inv*',       strategy: 'startsWith', core: 'inv',     literal: 'text'],
    [case: '*voice', in: '*voice',     strategy: 'endsWith',   core: 'voice',   literal: 'text'],
    [case: 'TRUE', in: 'TRUE',       strategy: 'exact',      core: 'TRUE',    literal: 'boolean'],
    [case: '-4.5', in: '-4.5',       strategy: 'exact',      core: '-4.5',    literal: 'number'],
    [case: '*5*', in: '*5*',        strategy: 'contains',   core: '5',       literal: 'text'],
]) { row ->
    def s = parseValueFilter(row.in)
    assert s.ok && !s.empty
    assert [s.strategy, s.core, s.literal] == [row.strategy, row.core, row.literal]
    assert s.value == row.in.trim()
}

table('values the two DataXray APIs would read differently are refused (VAL-3)', [
    [case: 'y?s', in: 'y?s',  says: "contains '?'"],
    [case: 'y*s', in: 'y*s',  says: 'in the middle'],
    [case: '**', in: '**',   says: 'only'],
]) { row ->
    def s = parseValueFilter(row.in)
    assert !s.ok && s.error.contains(row.says)
}

test('no value is no filter') {
    assert parseValueFilter('  ').empty
    assert parseValueFilter(null).empty
}

// --- on-prem: KQL ---------------------------------------------------------------------------

test('KQL: name and value on the same extractor entry, OR-ed across the picked extractors (VAL-1)') {
    def q = composeDxrQuery(['Contacts'], ['Contains PII?'], [], '', [], 'yes', ['Contains PII?', 'Is invoice?'])
    assert q == 'labels.name:"Contacts" AND extractedMetadata.name:"Contains PII?" AND ' +
        'extractedMetadata: { (name:"Contains PII?" OR name:"Is invoice?") AND value:"yes" }'
}

test('KQL: no extractors picked searches every extractor; wildcards and escaping kept') {
    assert composeDxrQuery([], [], [], '', [], '*say "hi"*', []) == 'extractedMetadata: { value:"*say \\"hi\\"*" }'
}

test('KQL: true/false and numbers also try the unquoted form (boolean and number extractors)') {
    assert composeDxrQuery([], [], [], '', [], 'True', ['Flag']) ==
        'extractedMetadata: { name:"Flag" AND (value:"True" OR value:true) }'
    assert composeDxrQuery([], [], [], '', [], '42', []) == 'extractedMetadata: { (value:"42" OR value:42) }'
}

// --- Cloud + Edge: query items --------------------------------------------------------------

def catalog = [indexIds: ['50', '34', '130', '7'], typeByIndexId: ['50': 'TEXT', '34': 'TEXT', '130': 'BOOLEAN', '7': 'NUMBER']]

table('Edge: * at the ends picks the match strategy; one group OR-ed across the picked extractors (VAL-1, VAL-2)', [
    [case: 'yes', v: 'yes',   strategy: 'exact',      value: 'yes'],
    [case: '*e*', v: '*e*',   strategy: 'contains',   value: 'e'],
    [case: 'n*', v: 'n*',    strategy: 'startsWith', value: 'n'],
    [case: '*S', v: '*S',    strategy: 'endsWith',   value: 'S'],
]) { row ->
    def r = composeEdgeQueryItems([labelIndexIds: ['9'], labelNames: ['Contacts'], valueFilter: row.v,
                                   valueFilterExtractorIndexIds: ['50', '34'], valueFilterExtractorNames: ['Contains PII?', 'Lorem']],
                                  [], catalog)
    assert r.unresolved == [] && r.invalid == []
    assert r.items.collect { [it.parameter, it.type, it.match_strategy, it.value, it.operator, it.group_id] } == [
        ['dxr#tags', 'number', 'exact', '9', 'AND', 0],
        ['extracted_metadata#50', 'text', row.strategy, row.value, 'AND', 1],
        ['extracted_metadata#34', 'text', row.strategy, row.value, 'OR', 1],
    ]
}

test('Edge: no extractors picked → every extractor whose data type can hold the value (VAL-4)') {
    def text = composeEdgeQueryItems([valueFilter: 'yes'], [], catalog)
    assert text.items*.parameter == ['extracted_metadata#50', 'extracted_metadata#34']
    def bool = composeEdgeQueryItems([valueFilter: 'true'], [], catalog)
    assert bool.items.collect { [it.parameter, it.type, it.value] } ==
        [['extracted_metadata#50', 'text', 'true'], ['extracted_metadata#34', 'text', 'true'], ['extracted_metadata#130', 'boolean', 'true']]
    def num = composeEdgeQueryItems([valueFilter: '5'], [], catalog)
    assert num.items.find { it.parameter == 'extracted_metadata#7' }.type == 'number'
}

test('Edge: a picked extractor whose data type cannot hold the value is refused, never sent (DataXray answers 500) (VAL-4)') {
    def r = composeEdgeQueryItems([valueFilter: 'yes', valueFilterExtractorIndexIds: ['130'], valueFilterExtractorNames: ['Sensitive data detector']],
                                  [], catalog)
    assert r.items == []
    assert r.invalid.size() == 1 && r.invalid[0].contains("'Sensitive data detector' returns boolean values") && r.invalid[0].contains('true or false')
}

test('Edge: an extractor without a DataXray Index ID is unresolved, as for every criterion') {
    def r = composeEdgeQueryItems([valueFilter: 'yes', valueFilterExtractorIndexIds: [''], valueFilterExtractorNames: ['Unsynced']], [], catalog)
    assert r.unresolved == ["extracted-value extractor 'Unsynced'"]
}

test('Edge: an unknown data type (classifications not synced since v2.12) is treated as text') {
    def r = composeEdgeQueryItems([valueFilter: 'yes', valueFilterExtractorIndexIds: ['99'], valueFilterExtractorNames: ['New']],
                                  [], [indexIds: ['99'], typeByIndexId: [:]])
    assert r.items.collect { [it.parameter, it.type] } == [['extracted_metadata#99', 'text']]
}
