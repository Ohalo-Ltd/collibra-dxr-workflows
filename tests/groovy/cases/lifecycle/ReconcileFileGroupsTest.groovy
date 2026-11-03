// reconcileFileGroups: a file's links to classifications follow DataXray's
// evidence; only links the integration made are ever changed. R16, R20;
// EVID-1, -2, -9; FILE-22; TRC story 21.

test('a classification DataXray reports gets a classified by link (EVID-1)') {
    def f = fileAsset('f1')
    def c = classification('PII')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 1, removed: 0, migrated: 0]
    assert linked(f, 'classifiedBy', c)
}

test('a link that stops applying becomes formerly classified by, never deleted (EVID-2)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'classifiedBy', c)

    assert reconcileFileGroups(f, [], ids, integrationIdentityIds()) == [added: 0, removed: 1, migrated: 0]
    assert !linked(f, 'classifiedBy', c)
    assert linked(f, 'formerlyGroups', c)
}

test('the classification applying again swaps formerly classified by back (FILE-22)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'formerlyGroups', c)

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 1, removed: 0, migrated: 0]
    assert linked(f, 'classifiedBy', c)
    assert !linked(f, 'formerlyGroups', c)
}

test('a link and its formerly twin never coexist after a swap') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'classifiedBy', c)
    link(f, 'formerlyGroups', c)

    reconcileFileGroups(f, [], ids, integrationIdentityIds())

    assert links('formerlyGroups', [source: f]).size() == 1
    assert links('classifiedBy', [source: f]).isEmpty()
}

test('a link a person made is kept when DataXray no longer reports it (EVID-9)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'classifiedBy', c, by: 'person')

    assert reconcileFileGroups(f, [], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 0]
    assert linked(f, 'classifiedBy', c)
    assert !linked(f, 'formerlyGroups', c)
}

test('a link a person made is not duplicated when DataXray reports it too (EVID-9)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'classifiedBy', c, by: 'person')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 0]
    assert links('classifiedBy', [source: f]).size() == 1
    assert fx.callCount('relationApi.addRelation') == 0
}

test('a formerly groups link a person made is kept when the classification applies again') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'formerlyGroups', c, by: 'person')

    reconcileFileGroups(f, [c], ids, integrationIdentityIds())

    assert linked(f, 'classifiedBy', c)
    assert linked(f, 'formerlyGroups', c)
}

test('links to other classifications are left as they are') {
    def f = fileAsset('f1')
    def kept = classification('Kept')
    def dropped = classification('Dropped')
    def added = classification('Added')
    link(f, 'classifiedBy', kept)
    link(f, 'classifiedBy', dropped)

    assert reconcileFileGroups(f, [kept, added], ids, integrationIdentityIds()) == [added: 1, removed: 1, migrated: 0]
    assert links('classifiedBy', [source: f])*.targetId as Set == [kept, added] as Set
    assert links('formerlyGroups', [source: f])*.targetId == [dropped]
}

// --- v2.11: "classified by / classifies" replaces "groups" for file links ------

test('LINK-1: an integration-made groups link that still applies is moved to classified by') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c)                                    // made by a release before v2.11

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 1]
    assert linked(f, 'classifiedBy', c)
    assert !linked(f, 'groups', c)
    assert !linked(f, 'formerlyGroups', c)
}

test('LINK-1: an integration-made groups link that no longer applies becomes formerly classified by') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c)

    assert reconcileFileGroups(f, [], ids, integrationIdentityIds()) == [added: 0, removed: 1, migrated: 0]
    assert !linked(f, 'groups', c) && !linked(f, 'classifiedBy', c)
    assert linked(f, 'formerlyGroups', c)
}

test('LINK-2: a person\'s groups link stays as it is and counts as linked (no duplicate)') {
    def f = fileAsset('f1')
    def c = classification('PII')
    def gone = classification('Old')
    link(f, 'groups', c, by: 'person')
    link(f, 'groups', gone, by: 'person')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 0]
    assert linked(f, 'groups', c) && linked(f, 'groups', gone)
    assert links('classifiedBy', [source: f]).isEmpty()
}

test('LINK-1: a groups twin of a classified by link is dropped') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'classifiedBy', c)
    link(f, 'groups', c)

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 1]
    assert linked(f, 'classifiedBy', c) && !linked(f, 'groups', c)
}

test('LINK-3: before Configure v2.11 creates the type, file links keep using groups') {
    fx.relationTypes.remove(ids.classifiedByRelationTypeId)
    def f = fileAsset('f1')
    def c = classification('PII')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 1, removed: 0, migrated: 0]
    assert linked(f, 'groups', c)
}

test('LINK-5: when the classified by replacement cannot be added, the groups link is kept for the next sync') {
    def f = fileAsset('f1')
    def c = classification('PII')
    link(f, 'groups', c)
    fx.failNext('relationApi.addRelation')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 0]
    assert linked(f, 'groups', c)                             // still classified, under the old name
    assert !linked(f, 'formerlyGroups', c)
    assert logged('WARN', 'replacement could not be added')

    assert reconcileFileGroups(f, [c], ids, integrationIdentityIds()) == [added: 0, removed: 0, migrated: 1]
    assert linked(f, 'classifiedBy', c) && !linked(f, 'groups', c)
}
