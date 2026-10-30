// syncClassificationCatalog: the DataXray catalogue mirrored into Collibra.
// TRC stories 5, 6, 8; R4, R6, R15; CAT-1..7, CAT-11. (The Edge catalogue
// requests are a separate transport; this is everything Collibra-side.)

def item(Map o) {
    [id: o.id ?: UUID.nameUUIDFromBytes("dxr:${o.name}".toString().bytes).toString(), name: o.name,
     type: o.type ?: 'LABEL', subtype: o.subtype ?: '', description: o.description ?: '',
     link: "/resource/${o.id}?type=${o.type ?: 'LABEL'}".toString(), indexId: o.indexId]
}

def sync(fake.World world, List items) { world.syncClassificationCatalog(items, 'https://dxr.example') }

def assetByDxrId(fake.World world, String dxrId) {
    world.fx.assets.values().find { a -> world.attr(a.id, 'dataxrayId') == dxrId }?.id
}

test('a new classification becomes an asset in the Classifications domain, tagged (CAT-1, story 5)') {
    def r = sync(delegate, [item(id: 'l-1', name: 'Contacts', type: 'LABEL'), item(id: 'a-1', name: 'Email', type: 'ANNOTATOR', subtype: 'Regular Expression')])

    assert r.created == 2 && r.failed == 0
    def label = assetByDxrId(delegate, 'l-1')
    assert fx.assets[label].name == 'Contacts'
    assert fx.assets[label].typeId == ids.labelTypeId && fx.assets[label].domainId == ids.classificationsDomainId
    assert fx.assets[label].tags.contains('dataxray-classification-sync')
    assert fx.assets[assetByDxrId(delegate, 'a-1')].typeId == ids.annotatorTypeId
}

test('a rename in DataXray renames the same asset, keeping its links (CAT-2, story 6)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts')])
    def label = assetByDxrId(delegate, 'l-1')
    def f = fileAsset('f1')
    link(f, 'groups', label)

    sync(delegate, [item(id: 'l-1', name: 'Contacted')])

    assert assetByDxrId(delegate, 'l-1') == label
    assert fx.assets[label].name == 'Contacted'
    assert linked(f, 'groups', label)
}

test('a classification deleted in DataXray is retired (Obsolete), never deleted (CAT-3, story 8)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])
    def gone = assetByDxrId(delegate, 'l-1')
    def f = fileAsset('f1')
    link(f, 'groups', gone)

    def r = sync(delegate, [item(id: 'l-2', name: 'Kept')])

    assert r.retired == 1
    assert status(gone) == 'Obsolete'
    assert linked(f, 'groups', gone)
}

test('a retired classification that reappears is reactivated (CAT-4)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])
    sync(delegate, [item(id: 'l-2', name: 'Kept')])

    sync(delegate, [item(id: 'l-1', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])

    assert status(assetByDxrId(delegate, 'l-1')) == 'Candidate'
}

test('re-created under the same name: a new asset; the old one keeps its history under a retired name (CAT-4a, story 8)') {
    sync(delegate, [item(id: 'l-old', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])
    def old = assetByDxrId(delegate, 'l-old')
    sync(delegate, [item(id: 'l-2', name: 'Kept')])

    sync(delegate, [item(id: 'l-new', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])

    def fresh = assetByDxrId(delegate, 'l-new')
    assert fresh != old
    assert fx.assets[fresh].name == 'Contacts' && status(fresh) == 'Candidate'
    assert fx.assets[old].name.startsWith('Contacts (retired ') && status(old) == 'Obsolete'
}

test('two classifications with one name get distinct names by subtype (CAT-5)') {
    sync(delegate, [item(id: 'a-1', name: 'VAT Number', type: 'ANNOTATOR', subtype: 'Named Entity'),
                    item(id: 'a-2', name: 'VAT Number', type: 'ANNOTATOR', subtype: 'Regular Expression')])

    assert fx.assets[assetByDxrId(delegate, 'a-1')].name == 'VAT Number (Named Entity)'
    assert fx.assets[assetByDxrId(delegate, 'a-2')].name == 'VAT Number (Regular Expression)'
}

test('an empty catalogue retires nothing (CAT-6)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts')])

    def r = sync(delegate, [])

    assert r.retired == 0
    assert status(assetByDxrId(delegate, 'l-1')) == 'Candidate'
}

test('a classification asset made by hand is never touched (CAT-7)') {
    def manual = classification('Made by hand', 'label', dxrId: 'not-in-dataxray')

    sync(delegate, [item(id: 'l-1', name: 'Contacts')])

    assert status(manual) == 'Candidate'
    assert fx.assets[manual].name == 'Made by hand'
}

test('a name held by a hand-made asset is not taken over; the item fails naming the clash') {
    def manual = fx.insertAsset(name: 'Contacts', domainId: ids.classificationsDomainId, typeId: ids.labelTypeId).id

    def r = sync(delegate, [item(id: 'l-1', name: 'Contacts')])

    assert r.failed == 1 && r.failures.any { it.contains('Contacts') }
    assert fx.assets[manual].name == 'Contacts'
}

test('a classification moved to another community is updated where it is, not duplicated (CAT-11, R4, R6)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts', description: 'v1')])
    def label = assetByDxrId(delegate, 'l-1')
    def elsewhere = otherDomain('Business Glossary')
    fx.assets[label].domainId = elsewhere
    def f = fileAsset('f1')
    link(f, 'groups', label)

    def r = sync(delegate, [item(id: 'l-1', name: 'Contacts renamed', description: 'v2')])

    assert r.created == 0
    assert fx.assets.values().count { it.typeId == ids.labelTypeId } == 1
    assert fx.assets[label].domainId == elsewhere
    assert fx.assets[label].name == 'Contacts renamed'
    assert linked(f, 'groups', label)
}

test('a moved classification deleted in DataXray is retired where it is (CAT-11)') {
    sync(delegate, [item(id: 'l-1', name: 'Contacts'), item(id: 'l-2', name: 'Kept')])
    def label = assetByDxrId(delegate, 'l-1')
    fx.assets[label].domainId = otherDomain('Business Glossary')

    sync(delegate, [item(id: 'l-2', name: 'Kept')])

    assert status(label) == 'Obsolete'
}
