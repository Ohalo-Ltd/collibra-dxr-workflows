// reconcileFileGroups: a file's links to classifications follow DataXray's
// evidence; only links the integration made are ever changed. R16, R20;
// EVID-1, -2, -9; FILE-22; TRC story 21.

test('a classification DataXray reports gets a groups link (EVID-1)') {
    def f = fileAsset('f1')
    def c = classification('PII')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 1, removed: 0]
    assert linked(f, 'groups', c)
}

test('a link that stops applying becomes formerly groups, never deleted (EVID-2)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c)

    assert reconcileFileGroups(f, [], ids, integrationIdentityIds()) == [added: 0, removed: 1]
    assert !linked(f, 'groups', c)
    assert linked(f, 'formerlyGroups', c)
}

test('the classification applying again swaps formerly groups back (FILE-22)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'formerlyGroups', c)

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 1, removed: 0]
    assert linked(f, 'groups', c)
    assert !linked(f, 'formerlyGroups', c)
}

test('a link and its formerly twin never coexist after a swap') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c)
    link(f, 'formerlyGroups', c)

    reconcileFileGroups(f, [], ids, integrationIdentityIds())

    assert links('formerlyGroups', [source: f]).size() == 1
    assert links('groups', [source: f]).isEmpty()
}

test('a link a person made is kept when DataXray no longer reports it (EVID-9)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c, by: 'person')

    assert reconcileFileGroups(f, [], ids, integrationIdentityIds()) == [added: 0, removed: 0]
    assert linked(f, 'groups', c)
    assert !linked(f, 'formerlyGroups', c)
}

test('a link a person made is not duplicated when DataXray reports it too (EVID-9)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c, by: 'person')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0]
    assert links('groups', [source: f]).size() == 1
    assert fx.callCount('relationApi.addRelation') == 0
}

test('a formerly groups link a person made is kept when the classification applies again') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'formerlyGroups', c, by: 'person')

    reconcileFileGroups(f, [c], ids, integrationIdentityIds())

    assert linked(f, 'groups', c)
    assert linked(f, 'formerlyGroups', c)
}

test('links to other classifications are left as they are') {
    def f = fileAsset('f1')
    def kept = classification('Kept')
    def dropped = classification('Dropped')
    def added = classification('Added')
    link(f, 'groups', kept)
    link(f, 'groups', dropped)

    assert reconcileFileGroups(f, [kept, added], ids, integrationIdentityIds()) == [added: 1, removed: 1]
    assert links('groups', [source: f])*.targetId as Set == [kept, added] as Set
    assert links('formerlyGroups', [source: f])*.targetId == [dropped]
}
