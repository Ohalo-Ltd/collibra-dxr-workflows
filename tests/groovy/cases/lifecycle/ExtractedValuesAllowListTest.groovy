// Which extractors' values are copied onto file assets (v2.12): Configure's
// "Sync extractor values to file assets" = true copies every extractor's,
// tagged copies only extractors tagged dataxray-sync-values. XV-1..4.

def valuesOn(fake.World w, String mode) {
    w.fx.attributeTypes[w.ids.extractedValuesAttrTypeId] = 'extractedValues'
    w.fx.attributeTypeDescriptions[w.ids.extractedValuesAttrTypeId] = w.dxrExtractedValuesDescription(mode == 'tagged')
}

def row() {
    [fileId: 'f1', datasource: [name: 'Share'], path: '/a/b.pdf',
     extractedMetadata: [[id: 'dxr-yes-no', name: 'Contains PII?', value: 'Yes'],
                         [id: 'dxr-ssn', name: 'National ID', value: '123-45-6789']]]
}

def hit() {
    [_id: 'f1', _source: [datasource_id: 312, object_id: 'o1', 'ds#file_name': 'b.pdf',
                          'extracted_metadata#50': 'Yes', 'extracted_metadata#77': '123-45-6789']]
}

def edgeIndex() {
    [labelDxrIdByIndexId: [:], annotatorDxrIdByIndexId: [:],
     extractorDxrIdByIndexId: ['50': 'dxr-yes-no', '77': 'dxr-ssn'],
     nameByDxrId: ['dxr-yes-no': 'Contains PII?', 'dxr-ssn': 'National ID']]
}

test('true: every extractor is copied, as before v2.12 (XV-1)') {
    valuesOn(delegate, 'all')
    assert extractedValuesMode() == 'all'
    assert extractFileTuple(row(), '')[10]*.get(0) == ['Contains PII?', 'National ID']
}

test('tagged: only extractors tagged dataxray-sync-values are copied — on-prem rows by DataXray ID (XV-2)') {
    valuesOn(delegate, 'tagged')
    classification('Contains PII?', 'extractor', dxrId: 'dxr-yes-no', indexId: '50', tags: ['dataxray-sync-values'])
    classification('National ID', 'extractor', dxrId: 'dxr-ssn', indexId: '77')
    assert extractedValuesMode() == 'tagged'
    assert extractFileTuple(row(), '')[10] == [['Contains PII?', 'Yes']]
}

test(variant: 'edge', 'tagged: Edge search hits are filtered by DataXray Index ID (XV-2)') {
    valuesOn(delegate, 'tagged')
    classification('Contains PII?', 'extractor', dxrId: 'dxr-yes-no', indexId: '50', tags: ['dataxray-sync-values'])
    classification('National ID', 'extractor', dxrId: 'dxr-ssn', indexId: '77')
    assert tupleFromSearchHit(hit(), edgeIndex(), ['312': 'SharePoint'], '')[10] == [['Contains PII?', 'Yes']]
}

test('tagged with no extractor tagged copies nothing — it never falls back to all (XV-3)') {
    valuesOn(delegate, 'tagged')
    classification('Contains PII?', 'extractor', dxrId: 'dxr-yes-no', indexId: '50')
    assert extractFileTuple(row(), '')[10] == []
}

test('untagging an extractor removes its values from a file on that file\'s next sync (XV-4)') {
    valuesOn(delegate, 'tagged')
    def pii = classification('Contains PII?', 'extractor', dxrId: 'dxr-yes-no', indexId: '50', tags: ['dataxray-sync-values'])
    def q = query('Contacts')
    processFileBatch([extractFileTuple(row(), '')], q, 'b')
    assert attr(assetIdOf('f1'), 'extractedValues').contains('Contains PII?')

    fx.assets[pii].tags.clear()
    ['dxrExtractedValueSources', 'dxrExtractedValuesMode'].each { vars.remove(it) }   // a new run
    processFileBatch([extractFileTuple(row(), '')], q, 'b')
    assert attr(assetIdOf('f1'), 'extractedValues') == null
}
