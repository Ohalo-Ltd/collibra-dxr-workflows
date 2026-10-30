// retireOrphanFiles: what a rerun does with the files its search stopped
// returning. TRC stories 15, 28, 29; acceptance criteria FILE-8, -9, -20, -21, -22.

test('a file only this search returned is unlinked and set to Not in any search (FILE-8)') {
    def q = query('Contacts')
    def f = fileAsset('f1')
    link(q, 'returns', f)

    def r = retireOrphanFiles([] as Set, [f.toString()], q)

    assert r == [unlinked: 1, retired: 1, parkedIds: [f.toString()]]
    assert !linked(q, 'returns', f)
    assert linked(q, 'formerlyReturns', f)
    assert status(f) == 'Not in any search'
}

test('a file another search still returns keeps its status and that link (FILE-9, story 28)') {
    def a = query('A')
    def b = query('B')
    def f = fileAsset('f1')
    link(a, 'returns', f)
    link(b, 'returns', f)

    def r = retireOrphanFiles([] as Set, [f.toString()], a)

    assert r == [unlinked: 1, retired: 0, parkedIds: []]
    assert linked(a, 'formerlyReturns', f)
    assert linked(b, 'returns', f)
    assert status(f) == 'Candidate'
}

test('a formerly returns link from another search does not keep a file in a search (FILE-21, story 29)') {
    def a = query('A')
    def b = query('B')
    def f = fileAsset('f1')
    link(a, 'returns', f)
    link(b, 'formerlyReturns', f)

    def r = retireOrphanFiles([] as Set, [f.toString()], a)

    assert r.retired == 1
    assert status(f) == 'Not in any search'
}

test('a status a person set is kept; the link is still swapped (FILE-20)') {
    def q = query('Contacts')
    def f = fileAsset('f1', status: ACCEPTED)
    link(q, 'returns', f)

    def r = retireOrphanFiles([] as Set, [f.toString()], q)

    assert r == [unlinked: 1, retired: 1, parkedIds: []]
    assert status(f) == 'Accepted'
    assert linked(q, 'formerlyReturns', f)
    assert fx.callCount('assetApi.changeAsset') == 0
    assert logged('INFO', 'set by a person')
}

test('a file already Not in DataXray keeps that status and is still handed to the existence check') {
    def q = query('Contacts')
    def f = fileAsset('f1', status: ids.notInDataXrayStatusId)
    link(q, 'returns', f)

    def r = retireOrphanFiles([] as Set, [f.toString()], q)

    assert r.parkedIds == [f.toString()]
    assert status(f) == 'Not in DataXray'
}

test('files the search still returns are not touched') {
    def q = query('Contacts')
    def f = fileAsset('f1')
    link(q, 'returns', f)

    def r = retireOrphanFiles([f.toString()] as Set, [f.toString()], q)

    assert r == [unlinked: 0, retired: 0, parkedIds: []]
    assert linked(q, 'returns', f)
    assert fx.callCount('relationApi.removeRelation') == 0
}

test('a file whose returns link someone already removed is still parked') {
    def q = query('Contacts')
    def f = fileAsset('f1')

    def r = retireOrphanFiles([] as Set, [f.toString()], q)

    assert r == [unlinked: 0, retired: 1, parkedIds: [f.toString()]]
    assert status(f) == 'Not in any search'
}

test('an existing formerly returns link is not duplicated (FILE-22)') {
    def q = query('Contacts')
    def f = fileAsset('f1')
    link(q, 'returns', f)
    link(q, 'formerlyReturns', f)

    retireOrphanFiles([] as Set, [f.toString()], q)

    assert links('formerlyReturns', [source: q, target: f]).size() == 1
    assert links('returns', [target: f]).isEmpty()
}

test('a failure on one file does not stop the others') {
    def q = query('Contacts')
    def f1 = fileAsset('f1')
    def f2 = fileAsset('f2')
    link(q, 'returns', f1)
    link(q, 'returns', f2)
    fx.before('assetApi.getAsset') { args -> if (args[0] == f1) { throw new RuntimeException('Collibra hiccup') } }

    def r = retireOrphanFiles([] as Set, [f1.toString(), f2.toString()], q)

    assert r.parkedIds == [f2.toString()]
    assert status(f2) == 'Not in any search'
    assert logged('ERROR', 'Collibra hiccup')
}

test('the relation lookups page through more than one page') {
    fx.maxPageSize = 2
    def q = query('Contacts')
    def files = (1..5).collect { fileAsset("f${it}") }
    files.each { link(q, 'returns', it) }

    def r = retireOrphanFiles([files[0].toString()] as Set, files*.toString(), q)

    assert r.unlinked == 4
    assert links('returns', [source: q])*.targetId == [files[0]]
}
