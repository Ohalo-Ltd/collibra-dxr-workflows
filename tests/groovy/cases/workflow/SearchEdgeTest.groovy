// Search DataXray, Cloud + Edge edition, run whole: the start form's values,
// the search and its results file, the results task's three options and the
// import. TRC stories 9, 10, 12, 13, 13a, 19; R1, R17, R19, R21; IMP-1..3,
// -6, -9, -11..13.

import java.util.zip.ZipInputStream

def search(fake.World world, String name, List<UUID> labels) { search([:], world, name, labels) }

def search(Map opts, fake.World world, String name, List<UUID> labels) {
    def r = world.run('search-data-xray', [vars: [conditionName: name, description: 'made by a test',
                                                  label: labels*.toString()] + (opts.vars ?: [:])])
    if (opts.expect) { assert r.state == opts.expect : r.toString() }
    return r
}

def zipEntries(byte[] bytes) {
    def out = [:]
    new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zin ->
        def e
        while ((e = zin.nextEntry) != null) { out[e.name] = new String(zin.readAllBytes(), 'UTF-8') }
    }
    return out
}

test(variant: 'edge', 'a search creates the sync filter with its criteria, preview and results file (IMP-1, IMP-12, R1)') {
    def label = classification('Contacts', 'label', indexId: '7')
    dxrFile('f1', labels: ['7'], ds: 312, objectId: 'Shared Documents/1/7', folder: '/Shared Documents', name: 'a.pdf')
    dxrFile('f2', labels: ['7'], ds: 312, objectId: 'Shared Documents/1/8', folder: '/Shared Documents', name: 'b.pdf')
    dxrFile('nope')

    def run = search(delegate, 'Contacts search', [label], expect: 'waiting')

    assert run.task.formKey == 'searchResultsForm'
    assert run.task.name.startsWith('Search Results: Contacts search (')
    assert run.task.missingFormVariables == []
    def q = UUID.fromString(run.var('queryAssetId'))
    assert fx.assets[q].name == 'Contacts search' && fx.assets[q].domainId == ids.queryDomainId
    assert linked(q, 'groups', label)
    assert attr(q, 'query')
    assert attr(q, 'files').contains('a.pdf')
    def zip = fx.attachments.find { it.baseResourceId == q }
    assert zip != null
    def csv = zipEntries(zip.bytes).values().first().readLines()
    assert csv[0] == 'Datasource,Path,Full Object ID'
    assert csv.drop(1) as Set == ['SharePoint,/Shared Documents/a.pdf,312:Shared Documents/1/7',
                                  'SharePoint,/Shared Documents/b.pdf,312:Shared Documents/1/8'] as Set
    assert fileAssets().isEmpty()
}

test(variant: 'edge', 'Import now imports every match and ends with an Import Summary (IMP-6, story 13a)') {
    def label = classification('Contacts', 'label', indexId: '7')
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])
    def run = search(delegate, 'Contacts search', [label], expect: 'waiting')

    run = complete(run, [importDecision: true, keepInSync: false, autoImportNewFiles: true])

    assert run.state == 'waiting' && run.task.formKey == 'importSummaryForm' : run.toString()
    assert run.task.name.startsWith('Import Summary: Contacts search (')
    assert run.task.missingFormVariables == []
    def q = UUID.fromString(run.var('queryAssetId'))
    assert fileAssets() as Set == [assetIdOf('f1'), assetIdOf('f2')] as Set
    assert fileAssets().every { linked(q, 'returns', it) && linked(it, 'groups', label) }
    assert run.var('importCreatedCount') == 2
    assert fx.assets[q].tags.isEmpty()
}

test(variant: 'edge', 'the sync options are saved on the sync filter, whatever is chosen about importing (IMP-9, IMP-11, R19)') {
    def label = classification('Contacts', 'label', indexId: '7')
    dxrFile('f1', labels: ['7'])
    def run = search(delegate, 'Contacts search', [label], expect: 'waiting')

    run = complete(run, [importDecision: false, keepInSync: true, autoImportNewFiles: false])

    assert run.state == 'completed'
    def q = UUID.fromString(run.var('queryAssetId'))
    assert fx.assets[q].tags == ['dataxray-keep-in-sync', 'dataxray-no-auto-import'] as Set
    assert fileAssets().isEmpty()
}

test(variant: 'edge', 'a search matching nothing offers no import (IMP-3)') {
    def label = classification('Contacts', 'label', indexId: '7')
    dxrFile('f1')

    def run = search(delegate, 'Empty search', [label], expect: 'waiting')

    assert run.var('resultCount') == 0
    assert run.var('importAllowed') == false
    assert run.task.missingFormVariables == []
}

test(variant: 'edge', 'IMP-13: more than 10,000 matches cannot be imported, even through the API') {
    def label = classification('Contacts', 'label', indexId: '7')
    (1..10_001).each { dxr.add([id: "f${it}".toString(), ds: 312, dsName: 'SharePoint', objectId: "o${it}".toString(),
                                name: "f${it}.pdf".toString(), labels: ['7']]) }
    dxr.maxResultWindow = 50_000
    def run = search(delegate, 'Huge search', [label], expect: 'waiting')
    assert run.var('importAllowed') == false
    assert run.var('importBlockedMessage').contains('more than the 10,000 a search can import')

    run = complete(run, [importDecision: true, keepInSync: false, autoImportNewFiles: true])

    assert run.state == 'error'
    assert run.error.message.contains('Importing is disabled for this search')
    assert fileAssets().isEmpty()
}

test(variant: 'edge', 'a search named like an existing sync filter is refused with a clear message') {
    def label = classification('Contacts', 'label', indexId: '7')
    query('Contacts search')

    def run = search(delegate, 'Contacts search', [label], expect: 'error')

    assert run.error instanceof com.collibra.dgc.workflow.api.exception.WorkflowException
    assert run.error.userMessage.startsWith("Could not create the search-query asset 'Contacts search'")
}

test(variant: 'edge', 'a classification retired in DataXray cannot be picked for a new search') {
    def label = classification('Contacts', 'label', indexId: '7', status: OBSOLETE)

    def run = search(delegate, 'Contacts search', [label], expect: 'error')

    assert run.failedAt == 'searchPrepareTask'
    assert run.error.userMessage.contains("label 'Contacts' was deleted in DataXray")
    assert fx.assets.values().every { it.typeId != ids.queryAssetTypeId }
}
