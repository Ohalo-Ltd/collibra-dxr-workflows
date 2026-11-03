// applyQueryFlags: the sync filter's two options as tags, written by the Search
// Results task and (v2.10) by every Rerun Options task — so they must be
// removable as well as addable (TRC, 2 Nov 2026).

table('the tags follow the options both ways (RERUN-OPT-3)', [
    [case: 'nothing → keep in sync, no auto-import', before: [], keep: true, auto: false,
     after: ['dataxray-keep-in-sync', 'dataxray-no-auto-import']],
    [case: 'both → neither', before: ['dataxray-keep-in-sync', 'dataxray-no-auto-import'], keep: false, auto: true,
     after: []],
    [case: 'unchanged', before: ['dataxray-keep-in-sync'], keep: true, auto: true,
     after: ['dataxray-keep-in-sync']],
]) { row ->
    def q = query('Contacts', tags: row.before)

    def r = applyQueryFlags(q, row.keep, row.auto)

    assert fx.assets[q].tags as Set == row.after as Set
    assert r == [keepInSync: row.keep, noAutoImport: !row.auto]
}

test('other tags on the sync filter are left alone') {
    def q = query('Contacts', tags: ['finance', 'dataxray-keep-in-sync'])
    applyQueryFlags(q, false, true)
    assert fx.assets[q].tags as Set == ['finance'] as Set
}
