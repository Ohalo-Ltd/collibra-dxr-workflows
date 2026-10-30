// The DataXray existence check's decisions (shared/file_exists_edge.groovy):
// which files are asked about, which become Not in DataXray, and the
// zero-results verdict. TRC stories 15, 31; R11, R24; FILE-11, FILE-28,
// NIGHT-8, NIGHT-10. The request/response loop itself is exercised by the
// workflow tests (cases/workflow).

def chunkOf(String ds, List<List> items) { [ds: ds, items: items.collect { [it[0].toString(), it[1]] }] }

test('the nightly asks about files Not in any search / Not in DataXray, per datasource') {
    def a = fileAsset('a', status: ids.notInAnySearchStatusId, ds: '312', obj: 'oa')
    def b = fileAsset('b', status: ids.notInDataXrayStatusId, ds: '312', obj: 'ob')
    def c = fileAsset('c', status: ids.notInAnySearchStatusId, ds: '400', obj: 'oc')
    fileAsset('active', ds: '312', obj: 'ox')                                   // Candidate: in a search
    fileAsset('accepted', status: ACCEPTED, ds: '312', obj: 'oy')               // a person's status
    fileAsset('legacy', status: ids.notInAnySearchStatusId)                     // no object key yet

    def w = buildExistsCheckWork(ids)

    assert w.candidates == 4 && w.noKey == 1
    assert w.chunks.collect { [it.ds, it.items*.getAt(0) as Set] } ==
        [['312', [a, b]*.toString() as Set], ['400', [c.toString()] as Set]]
}

test('a datasource with many files is asked about in chunks of dxrExistsCheckBatchSize()') {
    int n = dxrExistsCheckBatchSize() * 2 + 7
    (1..n).each { fileAsset("f${it}", status: ids.notInAnySearchStatusId, ds: '312', obj: "o${it}") }

    def w = buildExistsCheckWork(ids)

    assert w.chunks*.items*.size() == [dxrExistsCheckBatchSize(), dxrExistsCheckBatchSize(), 7]
}

test('a rerun asks only about the files it parked, still parked and still in Collibra') {
    def parked = fileAsset('parked', status: ids.notInAnySearchStatusId, ds: '312', obj: 'o1')
    def rematched = fileAsset('rematched', ds: '312', obj: 'o2')               // a rerun made it Candidate since
    def gone = fileAsset('gone', status: ids.notInAnySearchStatusId, ds: '312', obj: 'o3')
    fx.deleteAsset(gone)
    def eligible = { sid -> sid == ids.notInAnySearchStatusId || sid == ids.notInDataXrayStatusId }

    def w = buildExistsCheckWork(ids, [parked, rematched, gone]*.toString(), eligible)

    assert w.candidates == 1
    assert w.chunks[0].items == [[parked.toString(), 'o1']]
}

test('a chunk read to the end: unseen files Not in any search become Not in DataXray (story 31, NIGHT-8)') {
    def seen = fileAsset('seen', status: ids.notInAnySearchStatusId)
    def gone = fileAsset('gone', status: ids.notInAnySearchStatusId)
    def rematched = fileAsset('rematched')                                      // Candidate again meanwhile
    vars.existsMode = 'orphans'
    vars.existsSeen = groovy.json.JsonOutput.toJson([seen.toString()])
    vars.existsUnsafe = false

    def n = finishExistsCheckChunk(chunkOf('312', [[seen, 'o1'], [gone, 'o2'], [rematched, 'o3']]), ids)

    assert n == 1
    assert status(gone) == 'Not in DataXray'
    assert status(seen) == 'Not in any search'
    assert status(rematched) == 'Candidate'
    assert vars.existsNotInDataXray == 1
}

test('a chunk with an unreadable result flags nobody') {
    def gone = fileAsset('gone', status: ids.notInAnySearchStatusId)
    vars.existsMode = 'orphans'
    vars.existsSeen = '[]'
    vars.existsUnsafe = true

    assert finishExistsCheckChunk(chunkOf('312', [[gone, 'o2']]), ids) == 0
    assert status(gone) == 'Not in any search'
    assert logged('WARN', 'could not be read')
}

test('in zero-results mode a chunk flags nobody: the verdict comes at the end') {
    def gone = fileAsset('gone', status: ids.notInAnySearchStatusId)
    vars.existsMode = 'zeroResults'
    vars.existsSeen = '[]'
    vars.existsUnsafe = false

    assert finishExistsCheckChunk(chunkOf('312', [[gone, 'o2']]), ids) == 0
    assert status(gone) == 'Not in any search'
}

// --- the zero-results verdict ---------------------------------------------------------

// Three files the search returned: one only it returns, one another search also
// returns, one with a status a person set. `state`: what the check found.
def zeroResultsWorld(fake.World world, Map state) { world.with {
    def q = query('Contacts')
    def onlyHere = fileAsset('only-here', ds: '312', obj: 'o1')
    def alsoElsewhere = fileAsset('also-elsewhere', ds: '312', obj: 'o2')
    def accepted = fileAsset('accepted', status: ACCEPTED, ds: '312', obj: 'o3')
    [onlyHere, alsoElsewhere, accepted].each { link(q, 'returns', it) }
    link(query('Other search'), 'returns', alsoElsewhere)
    vars.existsWork = packJson([chunkOf('312', [[onlyHere, 'o1'], [alsoElsewhere, 'o2'], [accepted, 'o3']])])
    vars.existsCandidates = 3
    vars.existsNoKey = state.noKey ?: 0
    vars.existsFound = state.found ?: 0
    vars.existsComplete = state.complete != false
    vars.existsUnsafeAny = state.unsafe ?: false
    vars.existsNotInDataXray = 0
    return [q: q, onlyHere: onlyHere, alsoElsewhere: alsoElsewhere, accepted: accepted]
} }

test('zero results and DataXray has none of the files: all unlinked, Not in DataXray where ours (FILE-28)') {
    def w = zeroResultsWorld(delegate, [:])

    def r = concludeZeroResultsCheck(ids, w.q)

    assert r.allGone && r.unlinked == 3 && r.gone == 1
    assert [w.onlyHere, w.alsoElsewhere, w.accepted].every { !linked(w.q, 'returns', it) && linked(w.q, 'formerlyReturns', it) }
    assert status(w.onlyHere) == 'Not in DataXray'
    assert status(w.alsoElsewhere) == 'Candidate'
    assert status(w.accepted) == 'Accepted'
}

table('zero results: any doubt changes nothing (FILE-11)', [
    [case: 'DataXray still has one of them', found: 1],
    [case: 'a file has no object key', noKey: 1],
    [case: 'the check did not finish', complete: false],
    [case: 'a result could not be read', unsafe: true],
]) { row ->
    def w = zeroResultsWorld(delegate, row)

    def r = concludeZeroResultsCheck(ids, w.q)

    assert !r.allGone && r.unlinked == 0
    assert [w.onlyHere, w.alsoElsewhere, w.accepted].every { linked(w.q, 'returns', it) }
    assert status(w.onlyHere) == 'Candidate'
}

test('zero results with nothing to check changes nothing') {
    def q = query('Contacts')
    vars.existsCandidates = 0
    vars.existsComplete = true
    assert !concludeZeroResultsCheck(ids, q).allGone
}
