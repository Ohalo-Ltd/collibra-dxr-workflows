// Sync DataXray Files (Nightly), run whole in both editions: the fan-out of
// headless reruns, the orphan sweep and, in the Cloud + Edge edition, the
// DataXray existence check. TRC stories 11, 15, 17, 18, 28, 29, 31; R11, R24;
// NIGHT-2..5, -8..11; QRY-1..4.

def nightly(fake.World world) { nightly([:], world) }

def nightly(Map opts, fake.World world) {
    def r = world.run('sync-data-xray-files-nightly')
    assert r.state == (opts.expect ?: 'completed') : r.toString()
    return r
}

// --- fan-out --------------------------------------------------------------------------------

test('one headless rerun per keep-in-sync sync filter; retired ones are skipped (NIGHT-2, QRY-3, story 17)') {
    def synced = query('Synced', tags: ['dataxray-keep-in-sync'])
    query('Retired', tags: ['dataxray-keep-in-sync'], status: OBSOLETE)
    query('Not synced')

    def run = nightly(delegate)

    assert fx.startedInstances.size() == 1
    def started = fx.startedInstances[0]
    assert started.processId == 'rerunDataXraySearch'
    assert started.businessItemIds == [synced]
    assert started.formProperties == [headless: 'true']
    assert run.var('nightlyQueriesFound') == 2 && run.var('nightlyRetiredSkipped') == 1 && run.var('nightlyRerunsStarted') == 1
}

test('a restored sync filter is synced again (story 18)') {
    def q = query('Restored', tags: ['dataxray-keep-in-sync'], status: OBSOLETE)
    fx.assets[q].statusId = CANDIDATE

    nightly(delegate)

    assert fx.startedInstances*.businessItemIds == [[q]]
}

test('without the rerun workflow the fan-out is skipped and the sweep still runs (NIGHT-5)') {
    fx.workflowDefinitions.remove('rerunDataXraySearch')
    query('Synced', tags: ['dataxray-keep-in-sync'])
    def orphan = fileAsset('orphan')

    def run = nightly(delegate)

    assert fx.startedInstances.isEmpty()
    assert status(orphan) != 'Candidate'
    assert logged('ERROR', 'fan-out skipped')
}

test('a rerun that cannot be started does not stop the others') {
    def a = query('A', tags: ['dataxray-keep-in-sync'])
    def b = query('B', tags: ['dataxray-keep-in-sync'])
    fx.failNext('workflowInstanceApi.startWorkflowInstances')

    def run = nightly(delegate)

    assert fx.startedInstances*.businessItemIds == [[b]]
    assert run.var('nightlyFailedCount') == 1
}

// --- orphan sweep -----------------------------------------------------------------------------

test('a file no search returns any more is set to Not in any search (NIGHT-3, QRY-1)') {
    def q = query('Deleted later')
    def f = fileAsset('f1')
    link(q, 'returns', f)
    fx.deleteAsset(q)                                       // Collibra deletes its relations with it

    def run = nightly(delegate)

    assert status(f) in ['Not in any search', 'Not in DataXray']
    assert run.var('nightlyOrphansRetired') == 1
}

test('files a search returns, and files whose status a person set, are left alone (NIGHT-3)') {
    def q = query('Live')
    def returned = fileAsset('returned')
    link(q, 'returns', returned)
    def formerly = fileAsset('only-formerly')
    link(q, 'formerlyReturns', formerly)
    def accepted = fileAsset('accepted', status: ACCEPTED)

    nightly(delegate)

    assert status(returned) == 'Candidate'
    assert status(formerly) in ['Not in any search', 'Not in DataXray']
    assert status(accepted) == 'Accepted'
}

test('a file moved to another community is swept too (FILE-18)') {
    def f = fileAsset('moved', domainId: otherDomain())

    nightly(delegate)

    assert status(f) in ['Not in any search', 'Not in DataXray']
}

test('story 28/29 race: a file a rerun links while the sweep runs is not parked') {
    def q = query('Rerun running alongside')
    def f = fileAsset('f1')
    boolean linkedMeanwhile = false
    fx.before('relationApi.findRelations') { args ->
        def req = args[0]
        if (!linkedMeanwhile && req.props.targetId == f) {
            linkedMeanwhile = true
            fx.insertRelation(q, f, ids.returnsRelationTypeId)  // the rerun got there after the scan
        }
    }

    def run = nightly(delegate)

    assert linkedMeanwhile
    assert status(f) == 'Candidate'
    assert run.var('nightlyOrphansRetired') == 0
}

test('a failed relation scan aborts the sweep: nothing is parked on a partial picture (NIGHT-4)') {
    def q = query('Q')
    link(q, 'returns', fileAsset('f1'))
    link(q, 'returns', fileAsset('f2'))
    def orphan = fileAsset('orphan')
    fx.maxPageSize = 1                                      // two relations: the scan needs two pages
    int calls = 0
    fx.before('relationApi.findRelations') { args -> if (++calls == 2) { throw new RuntimeException('timeout on page 2') } }

    def run = nightly(delegate)

    assert run.var('nightlyOrphansRetired') == 0
    assert status(orphan) == 'Candidate'
    assert logged('ERROR', 'orphan sweep skipped: timeout on page 2')
}

test('before Configure has created the status, the sweep is skipped and the nightly still ends cleanly (ADM-4)') {
    fx.statuses.remove(ids.notInAnySearchStatusId)
    fx.statuses.remove(ids.notInDataXrayStatusId)
    def f = fileAsset('f1')

    nightly(delegate)

    assert status(f) == 'Candidate'
    assert logged('WARN', "the status 'Not in any search' does not exist")
}

test('files still Obsolete from a release before v2.4 move to Not in any search (NIGHT-7)') {
    def f = fileAsset('legacy', status: OBSOLETE)

    nightly(delegate)

    assert status(f) in ['Not in any search', 'Not in DataXray']
}

// --- the existence check (Cloud + Edge) ---------------------------------------------------------

test(variant: 'edge', 'story 31 / NIGHT-8: a parked file deleted in DataXray becomes Not in DataXray') {
    def gone = fileAsset('gone', status: ids.notInAnySearchStatusId, ds: '312', obj: 'obj-gone')
    def kept = fileAsset('kept', status: ids.notInAnySearchStatusId, ds: '312', obj: 'obj-kept')
    dxrFile('kept')

    def run = nightly(delegate)

    assert status(gone) == 'Not in DataXray'
    assert status(kept) == 'Not in any search'
    assert run.var('existsNotInDataXray') == 1 && run.var('existsComplete') == true
    def q = dxr.searchRequests()[0].body.filter.query_items
    assert q.find { it.parameter == 'datasource_id' }.value == '312'
    assert q.findAll { it.parameter == 'object_id' }*.value as Set == ['obj-gone', 'obj-kept'] as Set
}

test(variant: 'edge', 'NIGHT-9: a file back in DataXray goes back to Not in any search') {
    def back = fileAsset('back', status: ids.notInDataXrayStatusId, ds: '312', obj: 'obj-back')
    dxrFile('back')

    def run = nightly(delegate)

    assert status(back) == 'Not in any search'
    assert run.var('existsBackInDataXray') == 1
}

test(variant: 'edge', 'NIGHT-11: a file no search returns gets its classification links refreshed') {
    def label = classification('Contacts', 'label', indexId: '7')
    def email = classification('Email', 'annotator', indexId: '12')
    def f = fileAsset('f1', status: ids.notInAnySearchStatusId, ds: '312', obj: 'obj-f1')
    link(f, 'groups', label)                                // DataXray no longer has the label on it
    dxrFile('f1', annotators: ['12': 2], size: 4096)

    def run = nightly(delegate)

    assert !linked(f, 'groups', label) && linked(f, 'formerlyGroups', label)
    assert linked(f, 'groups', email)
    assert attr(f, 'fileSize') == '4.0 KB (4,096 bytes)'
    assert status(f) == 'Not in any search'
    assert run.var('existsRefreshed') == 1
}

test(variant: 'edge', 'NIGHT-10: files without an object key or with a person-set status are not checked') {
    def noKey = fileAsset('no-key', status: ids.notInAnySearchStatusId)
    def accepted = fileAsset('accepted', status: ACCEPTED, ds: '312', obj: 'obj-accepted')

    def run = nightly(delegate)

    assert status(noKey) == 'Not in any search'
    assert status(accepted) == 'Accepted'
    assert run.var('existsNoKey') == 1
    assert dxr.searchRequests().isEmpty()
}

test(variant: 'edge', 'the sweep and the check together: an orphan deleted in DataXray ends Not in DataXray the same night') {
    def q = query('Deleted later')
    def f = fileAsset('f1', ds: '312', obj: 'obj-f1')
    link(q, 'returns', f)
    fx.deleteAsset(q)

    nightly(delegate)

    assert status(f) == 'Not in DataXray'
}

test(variant: 'edge', 'DataXray unreachable: the check stops, flags nobody and the nightly still ends cleanly') {
    fileAsset('f1', status: ids.notInAnySearchStatusId, ds: '312', obj: 'obj-f1')
    3.times { dxr.failNext([error: 'IO exception occurred']) }

    def run = nightly(delegate)

    assert status(assetIdOf('f1')) == 'Not in any search'
    assert run.var('existsComplete') == false
}

test(variant: 'edge', 'the reruns the nightly starts run to completion') {
    def label = classification('Contacts', 'label', indexId: '7')
    def q = query('Synced', tags: ['dataxray-keep-in-sync'], labels: [label])
    dxrFile('f1', labels: ['7'])

    nightly(delegate)
    def reruns = runStarted()

    assert reruns*.state == ['completed']
    assert linked(q, 'returns', assetIdOf('f1'))
}
