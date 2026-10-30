// processFileBatch: one batch of DataXray rows upserted into file assets — what
// an import, a rerun and every Edge results page run. TRC stories 13a, 14, 19,
// 20, 22, 24; R1, R3, R19, R22, R23; IMP-6, -7; FILE-6, -10, -16, -19, -20,
// -24, -25, -26, -29.

test('a new file gets an asset with its attributes, a returns link and its classification links (IMP-6)') {
    def q = query('Contacts')
    def pii = classification('PII')
    def t = tuple('f1', name: 'Report.pdf', path: '/Shared/Report.pdf', size: '27885',
                  modified: '2025-04-10T17:22:00Z', classes: [pii], ds: '312', obj: 'Shared/1/7')

    def r = processFileBatch([t], q, 'Import batch 1/1')

    def f = assetIdOf('f1')
    assert r.subMap(['created', 'updated', 'failed', 'relationsAdded', 'skippedNew', 'matchedByObjectKey']) ==
        [created: 1, updated: 0, failed: 0, relationsAdded: 2, skippedNew: 0, matchedByObjectKey: 0]
    assert r.seenAssetIds == [f.toString()]
    def a = fx.assets[f]
    assert a.displayName == 'Report.pdf'
    assert a.name ==~ /Report\.pdf \[[0-9a-f]{8}\]/
    assert a.domainId == ids.filesDomainId && a.typeId == ids.fileTypeId
    assert status(f) == 'Candidate'
    assert attr(f, 'dataxrayId') == 'f1'
    assert attr(f, 'fileName') == 'Report.pdf'
    assert attr(f, 'filePath') == '/Shared/Report.pdf'
    assert attr(f, 'fileSize') == '27.2 KB (27,885 bytes)'
    assert attr(f, 'lastModified') == '10 Apr 2025, 17:22 UTC'
    assert attr(f, 'datasource') == 'SharePoint'
    assert linked(q, 'returns', f)
    assert linked(f, 'groups', pii)
}

test('Cloud + Edge rows store the object key and the Full Object ID (R1, FILE-25, FILE-29)') {
    def q = query('Contacts')
    processFileBatch([tuple('f1', ds: '312', obj: 'Shared Documents/1/7')], q, 'b')
    def f = assetIdOf('f1')
    assert attr(f, 'datasourceId') == '312'
    assert attr(f, 'objectId') == 'Shared Documents/1/7'
    assert attr(f, 'objectKey') == '312:Shared Documents/1/7'
}

test('without Configure v2.9 there is no Full Object ID, and nothing else changes (FILE-29)') {
    fx.attributeTypes.remove(ids.objectKeyAttrTypeId)
    def q = query('Contacts')
    def r = processFileBatch([tuple('f1', ds: '312', obj: 'o1')], q, 'b')
    def f = assetIdOf('f1')
    assert r.failed == 0
    assert attr(f, 'objectKey') == null
    assert attr(f, 'objectId') == 'o1'
}

test('on-prem rows carry no object key: no key attributes are written') {
    def q = query('Contacts')
    processFileBatch([tuple('f1')], q, 'b')
    def f = assetIdOf('f1')
    assert attr(f, 'datasourceId') == null && attr(f, 'objectId') == null && attr(f, 'objectKey') == null
}

table('a parked file a search matches again goes back to Candidate and its link is swapped back (FILE-10)', [
    [case: 'Not in any search', status: 'notInAnySearch'],
    [case: 'Not in DataXray', status: 'notInDataXray'],
    [case: 'Obsolete (before v2.4)', status: 'obsolete'],
]) { row ->
    def q = query('Contacts')
    def sid = row.status == 'obsolete' ? OBSOLETE : ids["${row.status}StatusId".toString()]
    def f = fileAsset('f1', status: sid)
    link(q, 'formerlyReturns', f)

    def r = processFileBatch([tuple('f1')], q, 'b')

    assert r.updated == 1 && r.created == 0
    assert status(f) == 'Candidate'
    assert linked(q, 'returns', f)
    assert !linked(q, 'formerlyReturns', f)
}

test('a status a person set survives a sync; attributes and links still refresh (FILE-20)') {
    def q = query('Contacts')
    def pii = classification('PII')
    def f = fileAsset('f1', status: ACCEPTED)

    processFileBatch([tuple('f1', classes: [pii], size: '2048')], q, 'b')

    assert status(f) == 'Accepted'
    assert attr(f, 'fileSize') == '2.0 KB (2,048 bytes)'
    assert linked(f, 'groups', pii)
    assert linked(q, 'returns', f)
}

test('a rename in Collibra sticks; DataXray File Name follows DataXray (R23, FILE-26)') {
    def q = query('Contacts')
    def f = fileAsset('f1', name: 'old.pdf')
    fx.assets[f].name = 'Board minutes (final)'
    fx.assets[f].displayName = 'Board minutes (final)'

    processFileBatch([tuple('f1', name: 'renamed-in-dataxray.pdf')], q, 'b')

    assert fx.assets[f].name == 'Board minutes (final)'
    assert fx.assets[f].displayName == 'Board minutes (final)'
    assert attr(f, 'fileName') == 'renamed-in-dataxray.pdf'
}

test('a file another search imported is reused, never duplicated (IMP-7, R3)') {
    def a = query('A')
    def b = query('B')
    def f = fileAsset('f1')
    link(a, 'returns', f)

    def r = processFileBatch([tuple('f1')], b, 'b')

    assert r.created == 0 && r.updated == 1
    assert fileAssets() == [f]
    assert linked(a, 'returns', f) && linked(b, 'returns', f)
}

test('a file moved to another community is updated where it is (FILE-16, story 22)') {
    def q = query('Contacts')
    def elsewhere = otherDomain('Finance Documents')
    def f = fileAsset('f1', domainId: elsewhere)

    def r = processFileBatch([tuple('f1', size: '4096')], q, 'b')

    assert r.created == 0
    assert fileAssets() == [f]
    assert fx.assets[f].domainId == elsewhere
    assert attr(f, 'fileSize') == '4.0 KB (4,096 bytes)'
}

test('a file DataXray gave a new id keeps its asset, found by object key (FILE-25)') {
    def q = query('Contacts')
    def old = fileAsset('old-id', ds: '312', obj: 'Shared/1/7')
    link(q, 'returns', old)

    def r = processFileBatch([tuple('new-id', ds: '312', obj: 'Shared/1/7')], q, 'b')

    assert r.created == 0 && r.matchedByObjectKey == 1
    assert r.seenAssetIds == [old.toString()]
    assert fileAssets() == [old]
    assert attr(old, 'dataxrayId') == 'new-id'
}

test('the object-key index is read from Collibra once per run, then cached') {
    def q = query('Contacts')
    fileAsset('old-1', ds: '312', obj: 'o1')
    fileAsset('old-2', ds: '312', obj: 'o2')

    processFileBatch([tuple('new-1', ds: '312', obj: 'o1')], q, 'b1')
    int scans = fx.callCount('attributeApi.findAttributes')
    processFileBatch([tuple('new-2', ds: '312', obj: 'o2')], q, 'b2')

    assert vars.dxrObjectKeyIndex
    assert fx.callCount('attributeApi.findAttributes') == scans
    assert fileAssets().size() == 2
}

test('the same object id in another datasource is a different file') {
    def q = query('Contacts')
    def other = fileAsset('other', ds: '400', obj: 'o1')

    def r = processFileBatch([tuple('f1', ds: '312', obj: 'o1')], q, 'b')

    assert r.created == 1 && r.matchedByObjectKey == 0
    assert fileAssets().size() == 2
}

test('automatic import off: new files are skipped, existing ones refreshed (R19, FILE-19)') {
    def q = query('Contacts')
    def f = fileAsset('known')

    def r = processFileBatch([tuple('known'), tuple('new')], q, 'b', [createNew: false])

    assert r.created == 0 && r.updated == 1 && r.skippedNew == 1
    assert fileAssets() == [f]
    assert linked(q, 'returns', f)
}

test('syncing the same batch twice changes nothing the second time') {
    def q = query('Contacts')
    def pii = classification('PII')
    processFileBatch([tuple('f1', classes: [pii])], q, 'b')
    int relations = fx.relations.size()

    def again = processFileBatch([tuple('f1', classes: [pii])], q, 'b')

    assert again.created == 0 && again.relationsAdded == 0 && again.relationsRemoved == 0
    assert fx.relations.size() == relations
}

test('a refused bulk relation add falls back to adding one by one') {
    def q = query('Contacts')
    def pii = classification('PII')
    fx.failNext('relationApi.addRelations')

    def r = processFileBatch([tuple('f1', classes: [pii])], q, 'b')

    def f = assetIdOf('f1')
    assert r.relationsAdded == 2
    assert linked(q, 'returns', f) && linked(f, 'groups', pii)
    assert logged('WARN', 'retrying individually')
}

test('a batch that fails is counted as failed and does not throw') {
    def q = query('Contacts')
    fx.failNext('assetApi.addAssets', new RuntimeException('Collibra is down'))

    def r = processFileBatch([tuple('f1'), tuple('f2')], q, 'Import batch 3/9')

    assert r.failed == 2 && r.created == 0
    assert fileAssets().isEmpty()
    assert logged('ERROR', 'Import batch 3/9 failed: Collibra is down')
}

test('extractor values are written as a table only while the attribute type exists (v2.8)') {
    def q = query('Contacts')
    processFileBatch([tuple('f1', values: [['IBAN', 'NL91 ABNA 0417 1643 00']])], q, 'b')
    assert attr(assetIdOf('f1'), 'extractedValues') == null

    fx.attributeTypes[ids.extractedValuesAttrTypeId] = 'extractedValues'
    vars.remove('dxrExtractedValuesOn')
    processFileBatch([tuple('f2', values: [['IBAN', 'NL91 ABNA 0417 1643 00']])], q, 'b')
    def html = attr(assetIdOf('f2'), 'extractedValues')
    assert html.contains('<td') && html.contains('IBAN') && html.contains('NL91 ABNA 0417 1643 00')
}

test('extractor values are cleared when the file no longer has any') {
    fx.attributeTypes[ids.extractedValuesAttrTypeId] = 'extractedValues'
    def q = query('Contacts')
    processFileBatch([tuple('f1', values: [['IBAN', 'NL91']])], q, 'b')
    processFileBatch([tuple('f1', values: [])], q, 'b')
    assert attr(assetIdOf('f1'), 'extractedValues') == null
}
