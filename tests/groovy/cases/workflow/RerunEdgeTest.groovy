// Rerun DataXray Search, Cloud + Edge edition, run whole: the built BPMN and
// its scripts against FakeCollibra and FakeDataXray, from the collector's
// guards through the results pages, the retire pass and the existence check
// to the summary task. TRC stories 8, 11, 14, 15, 17, 20, 28, 29, 31;
// R1, R11, R19, R21, R24; FILE-2..5, -8..11, -19, -23, -27, -28.

// A label criterion (index id 7) and an annotator (index id 12) the files can carry.
def contactsSearch(fake.World world) { contactsSearch([:], world) }

def contactsSearch(Map opts, fake.World world) { world.with {
    def label = classification('Contacts', 'label', indexId: '7')
    def email = classification('Email', 'annotator', indexId: '12')
    def q = query(opts.name ?: 'Contacts search', [labels: [label]] + (opts.query ?: [:]))
    return [q: q, label: label, email: email]
} }

def rerun(fake.World world, UUID q) { rerun([:], world, q) }

// opts: headless (boolean), expect (the state the run must end in),
// options (the Rerun Options task's values; default: send it as prefilled),
// atOptions (true: stop at the Rerun Options task instead of sending it).
def rerun(Map opts, fake.World world, UUID q) {
    def r = world.run('rerun-data-xray-search', [item: q, vars: opts.headless ? [headless: 'true'] : [:]])
    if (!opts.atOptions && r.state == 'waiting' && r.task.formKey == 'rerunOptionsForm') {
        assert r.task.missingFormVariables == [] : r.task.toString()
        r = world.complete(r, opts.options ?: [importDecision    : r.var('importDecision'),
                                               keepInSync        : r.var('keepInSync'),
                                               autoImportNewFiles: r.var('autoImportNewFiles')])
    }
    if (opts.expect != null) { assert r.state == opts.expect : r.toString() }
    return r
}

test(variant: 'edge', 'a rerun imports the matching files and stops at its summary (stories 14, 20)') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'], annotators: ['12': 3])
    dxrFile('unrelated')

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert run.task.formKey == 'rerunSummaryForm'
    assert run.task.name ==~ /Rerun Summary: Contacts search \(\d{1,2} \w{3} \d{4}, \d\d:\d\d UTC\)/
    assert run.task.missingFormVariables == []
    assert run.var('importCreatedCount') == 2
    assert fileAssets() as Set == [assetIdOf('f1'), assetIdOf('f2')] as Set
    assert linked(s.q, 'returns', assetIdOf('f1'))
    assert linked(assetIdOf('f2'), 'classifiedBy', s.label) && linked(assetIdOf('f2'), 'classifiedBy', s.email)
}

test(variant: 'edge', 'file assets carry the Full Object ID, datasource name and readable size (R1, R22)') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'], ds: 312, dsName: 'Finance SharePoint', objectId: 'Shared Documents/1/7', size: 27885)

    rerun(delegate, s.q, expect: 'waiting')

    def f = assetIdOf('f1')
    assert attr(f, 'objectKey') == '312:Shared Documents/1/7'
    assert attr(f, 'datasource') == 'Finance SharePoint'
    assert attr(f, 'fileSize') == '27.2 KB (27,885 bytes)'
    assert dxr.requests.count { it.path == '/api/datasources/312' } == 1
}

test(variant: 'edge', 'a headless rerun (the nightly) ends without a summary task') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])

    def run = rerun(delegate, s.q, headless: true, expect: 'completed')

    assert run.tasks.isEmpty()
    assert linked(s.q, 'returns', assetIdOf('f1'))
}

test(variant: 'edge', 'the rerun refreshes the sync filter Files preview') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])

    rerun(delegate, s.q, expect: 'waiting')

    assert attr(s.q, 'files').contains('first 2 of 2')
}

// --- the TRC 28 Oct review: stories 28, 29, 31 ------------------------------------------

test(variant: 'edge', 'story 28/29: a file whose label was removed is parked and its links refreshed before the summary') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    def f2 = dxrFile('f2', labels: ['7'], annotators: ['12': 3])
    rerun(delegate, s.q, expect: 'waiting')
    f2.labels = []                                         // the label is removed in DataXray

    def run = rerun(delegate, s.q, expect: 'waiting')

    def a2 = assetIdOf('f2')
    assert status(a2) == 'Not in any search'
    assert !linked(s.q, 'returns', a2) && linked(s.q, 'formerlyReturns', a2)
    assert !linked(a2, 'classifiedBy', s.label) && linked(a2, 'formerlyGroups', s.label)
    assert linked(a2, 'classifiedBy', s.email)
    assert run.var('rerunExistsRan') == true && run.var('existsRefreshed') == 1
    assert status(assetIdOf('f1')) == 'Candidate'
    assert run.task.missingFormVariables == []
}

test(variant: 'edge', 'story 31: a file deleted in DataXray is Not in DataXray when the rerun ends') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    dxr.remove('f2')

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert status(assetIdOf('f2')) == 'Not in DataXray'
    assert linked(s.q, 'formerlyReturns', assetIdOf('f2'))
    assert run.var('existsNotInDataXray') == 1
    assert status(assetIdOf('f1')) == 'Candidate'
}

test(variant: 'edge', 'story 28: a file another search still returns stays Candidate and is not checked') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    def f2 = dxrFile('f2', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    link(query('Another search'), 'returns', assetIdOf('f2'))
    f2.labels = []

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert status(assetIdOf('f2')) == 'Candidate'
    assert linked(s.q, 'formerlyReturns', assetIdOf('f2'))
    assert run.var('rerunExistsRan') == false
}

test(variant: 'edge', 'a person-set status survives the rerun dropping the file (FILE-20)') {
    def s = contactsSearch(delegate)
    def f1 = dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])                            // keeps the results non-empty
    rerun(delegate, s.q, expect: 'waiting')
    fx.assets[assetIdOf('f1')].statusId = ACCEPTED
    f1.labels = []

    rerun(delegate, s.q, expect: 'waiting')

    assert status(assetIdOf('f1')) == 'Accepted'
    assert linked(s.q, 'formerlyReturns', assetIdOf('f1'))
}

test(variant: 'edge', 'a file matching again is reactivated and its link swapped back (FILE-10)') {
    def s = contactsSearch(delegate)
    def f1 = dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    f1.labels = []
    rerun(delegate, s.q, expect: 'waiting')
    assert status(assetIdOf('f1')) == 'Not in any search'
    f1.labels = ['7']

    rerun(delegate, s.q, expect: 'waiting')

    assert status(assetIdOf('f1')) == 'Candidate'
    assert linked(s.q, 'returns', assetIdOf('f1')) && !linked(s.q, 'formerlyReturns', assetIdOf('f1'))
    assert linked(assetIdOf('f1'), 'classifiedBy', s.label) && !linked(assetIdOf('f1'), 'formerlyGroups', s.label)
}

// --- zero results --------------------------------------------------------------------------

test(variant: 'edge', 'FILE-28: 0 results and DataXray has none of the files: unlinked and Not in DataXray') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    dxr.remove('f1')
    dxr.remove('f2')                                        // the whole datasource was deleted

    def run = rerun(delegate, s.q, expect: 'waiting')

    [assetIdOf('f1'), assetIdOf('f2')].each { f ->
        assert status(f) == 'Not in DataXray'
        assert !linked(s.q, 'returns', f) && linked(s.q, 'formerlyReturns', f)
    }
    assert run.var('rerunExistsNoted') == true
    assert run.var('rerunExistsNote').contains('has none of them any more')
    assert run.var('rerunRetireSkipped') == false
    assert run.task.missingFormVariables == []
}

test(variant: 'edge', 'FILE-11: 0 results while DataXray still has the files: nothing is unlinked') {
    def s = contactsSearch(delegate)
    def f1 = dxrFile('f1', labels: ['7'])
    def f2 = dxrFile('f2', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    f1.labels = []
    f2.labels = []

    def run = rerun(delegate, s.q, expect: 'waiting')

    [assetIdOf('f1'), assetIdOf('f2')].each { f ->
        assert status(f) == 'Candidate'
        assert linked(s.q, 'returns', f)
    }
    assert run.var('rerunRetireSkipped') == true
    assert run.var('rerunRetireSkipReason').contains('still has 2 of the 2')
}

// --- refusals and aborts ---------------------------------------------------------------------

test(variant: 'edge', 'FILE-2: a criterion deleted in DataXray stops an interactive rerun, naming it') {
    def s = contactsSearch(delegate)
    fx.assets[s.label].statusId = OBSOLETE
    dxrFile('f1', labels: ['7'])

    def run = rerun(delegate, s.q, expect: 'error')

    assert run.failedAt == 'rerunCollectorTask'
    assert run.error instanceof com.collibra.dgc.workflow.api.exception.WorkflowException
    assert run.error.userMessage.contains("'Contacts' was deleted in DataXray")
    assert run.httpCalls.isEmpty() && fileAssets().isEmpty()
}

test(variant: 'edge', 'FILE-3: a headless rerun with a dead criterion ends cleanly without calling DataXray (29 Oct fix)') {
    def s = contactsSearch(delegate)
    fx.assets[s.label].statusId = OBSOLETE

    def run = rerun(delegate, s.q, headless: true, expect: 'completed')

    assert run.httpCalls.isEmpty()
    assert run.var('rerunAborted') == true
    assert logged('ERROR', "'Contacts' was deleted in DataXray")
}

test(variant: 'edge', 'FILE-4: a sync filter with no criteria left is refused, never run as an all-files search') {
    def q = query('Empty search')
    dxrFile('f1')

    def run = rerun(delegate, q, expect: 'error')

    assert run.error.userMessage.contains('no linked classifications, no annotated-text filter and no extracted-value filter')
    assert dxr.requests.isEmpty()
}

test(variant: 'edge', 'FILE-5: a rerun started on a file asset is refused, naming its type') {
    def f = fileAsset('f1')

    def run = rerun(delegate, f, expect: 'error')

    assert run.error.userMessage.contains('is a DataXray File asset')
}

test(variant: 'edge', 'ADM-4: before Configure has run, the rerun refuses and says so') {
    def s = contactsSearch(delegate)
    fx.statuses.remove(ids.notInAnySearchStatusId)

    def run = rerun(delegate, s.q, expect: 'error')

    assert run.error.userMessage.contains('Run Configure DataXray Workflows')
}

test(variant: 'edge', 'story 17: a retired sync filter can still be rerun by hand (QRY-3)') {
    def s = contactsSearch(delegate, query: [status: OBSOLETE])
    dxrFile('f1', labels: ['7'])

    rerun(delegate, s.q, expect: 'waiting')

    assert linked(s.q, 'returns', assetIdOf('f1'))
}

test(variant: 'edge', 'DataXray unreachable: the rerun gives up after 3 attempts and unlinks nothing') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    3.times { dxr.failNext([error: 'IO exception occurred']) }

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert run.var('rerunAborted') == true
    assert run.var('rerunAbortReason').contains('IO exception occurred')
    assert linked(s.q, 'returns', assetIdOf('f1')) && status(assetIdOf('f1')) == 'Candidate'
    assert run.task.missingFormVariables == []
}

test(variant: 'edge', 'one failed request is retried and the rerun completes') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxr.failNext([status: 502, body: '{"error":"bad gateway"}', reason: 'Bad Gateway'])

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert run.var('rerunAborted') == false
    assert linked(s.q, 'returns', assetIdOf('f1'))
}

// --- response size (Collibra's 100 KB External API limit) ---------------------------------------

table(variant: 'edge', 'pages too large for Collibra are asked for again with fewer rows', [
    [case: 'explicit refusal', empty500: false],
    [case: 'HTTP 500 with an empty body (TRC, 9 Oct 2026)', empty500: true],
]) { row ->
    def s = contactsSearch(delegate)
    (1..30).each { dxrFile("f${it}", labels: ['7'], rowBytes: 5_000) }
    dxr.maxResponseBytes = 60_000
    dxr.refusalAsEmpty500 = row.empty500

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert fileAssets().size() == 30
    assert run.var('rerunAborted') == false && run.var('rerunRetireSkipped') == false
    assert logged('WARN', "exceed Collibra's response size limit")
}

test(variant: 'edge', 'a single result too large to fetch is skipped but still counts as returned') {
    def s = contactsSearch(delegate)
    dxrFile('big', labels: ['7'])
    dxrFile('f1', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    dxr.files.find { it.id == 'big' }.rowBytes = 200_000

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert linked(s.q, 'returns', assetIdOf('big')) && status(assetIdOf('big')) == 'Candidate'
    assert run.var('importFailedCount') == 1
    assert logged('WARN', 'skipped result')
}

test(variant: 'edge', 'a skipped result with no asset makes the retire pass stand down for the run') {
    def s = contactsSearch(delegate)
    dxrFile('old', labels: ['7'])
    rerun(delegate, s.q, expect: 'waiting')
    dxr.remove('old')
    dxrFile('new-and-huge', labels: ['7'], rowBytes: 200_000)

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert linked(s.q, 'returns', assetIdOf('old')) && status(assetIdOf('old')) == 'Candidate'
    assert run.var('rerunRetireSkipped') == true
    assert run.var('rerunRetireSkipReason').contains('too large')
}

// --- import limits and options -------------------------------------------------------------------

test(variant: 'edge', 'R19 / FILE-19: tagged dataxray-no-auto-import, new files are not imported') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-no-auto-import']])
    def known = fileAsset('known')
    dxrFile('known', labels: ['7'])
    dxrFile('new', labels: ['7'])

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert fileAssets() == [known]
    assert linked(s.q, 'returns', known)
    assert run.var('importSkippedNewCount') == 1
}

def manyFiles(fake.World world, int n) {
    (1..n).each { world.dxr.add([id: "f${it}".toString(), ds: 312, dsName: 'SharePoint', objectId: "o${it}".toString(),
                                 name: "f${it}.pdf".toString(), labels: ['7']]) }
}

test(variant: 'edge', 'a rerun matching more files than DataXray can page through is refused') {
    def s = contactsSearch(delegate)
    manyFiles(delegate, 10_001)

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert run.var('rerunAborted') == true
    assert run.var('rerunAbortReason').contains('more than the 10000 results DataXray can page through')
    assert fileAssets().isEmpty()
}

test(variant: 'edge', 'R21 / FILE-23: creating more than 10,000 assets is refused on the Rerun Options task; refreshing still works') {
    def s = contactsSearch(delegate)
    dxr.maxResultWindow = 50_000                            // an instance with a raised paging window
    (1..10_001).each { dxr.add([id: "f${it}".toString(), ds: 312, dsName: 'SharePoint', objectId: "o${it}".toString(),
                                name: "f${it}.pdf".toString(), labels: ['7']]) }

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')

    assert run.task.formKey == 'rerunOptionsForm'
    assert run.var('importBlocked') == true && run.var('importAllowed') == false && run.var('importDecision') == false
    assert run.var('importBlockedMessage').contains('more than the 10,000 one run may create')
    assert run.var('syncOffered') == true                    // the lasting options stay changeable

    def refused = complete(run, [importDecision: true, keepInSync: false, autoImportNewFiles: true])
    assert refused.state == 'error'
    assert refused.error.message.contains('more than the 10,000 one run may create')
    assert fileAssets().isEmpty()
    assert fx.assets[s.q].tags.isEmpty()                     // refused before any option was written

    def refreshOnly = rerun(delegate, s.q, expect: 'waiting') // sent as prefilled: create nothing
    assert refreshOnly.task.formKey == 'rerunSummaryForm'
    assert refreshOnly.var('rerunAborted') == false
    assert refreshOnly.var('importSkippedNewCount') == 10_001
    assert fileAssets().isEmpty()
}

// --- the Rerun Options task (v2.10) ------------------------------------------------

test(variant: 'edge', 'RERUN-OPT-1: the Rerun Options task shows today\'s count and the options as the tags have them') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-keep-in-sync']])
    def known = fileAsset('known')
    link(s.q, 'returns', known)
    dxrFile('known', labels: ['7'])
    dxrFile('new', labels: ['7'])

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')

    assert run.task.formKey == 'rerunOptionsForm'
    assert run.task.name ==~ /Rerun Options: Contacts search \(\d{1,2} \w{3} \d{4}, \d\d:\d\d UTC\)/
    assert run.task.missingFormVariables == []
    assert run.var('rerunResultCountDisplay') == '2'
    assert run.var('rerunNewFilesDisplay') == 'at least 1'
    assert run.var('rerunPreviousCountDisplay') == '1'
    assert [run.var('importDecision'), run.var('keepInSync'), run.var('autoImportNewFiles')] == [true, true, true]
    assert fileAssets() == [known]                            // nothing changed before Send
    assert attr(s.q, 'query') == null                        // not even the DataXray Query attribute
    def counted = dxr.searchRequests()
    assert counted.size() == 1
    assert counted[0].body.pageSize == 1 && counted[0].body.excludedFields == ['*']
}

test(variant: 'edge', 'RERUN-OPT-2: a search whose files were not imported at first can import them on a rerun') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-no-auto-import']])
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')
    assert run.var('importDecision') == false                 // prefilled from dataxray-no-auto-import

    run = complete(run, [importDecision: true, keepInSync: false, autoImportNewFiles: false])

    assert run.state == 'waiting' && run.task.formKey == 'rerunSummaryForm'
    assert run.var('importCreatedCount') == 2
    assert fileAssets() as Set == [assetIdOf('f1'), assetIdOf('f2')] as Set
    assert fx.assets[s.q].tags as Set == ['dataxray-no-auto-import'] as Set
    assert attr(s.q, 'query')                                 // recorded once the options were applied
}

test(variant: 'edge', 'RERUN-OPT-2: unticking the first option refreshes only the files already in Collibra') {
    def s = contactsSearch(delegate)
    def known = fileAsset('known')
    dxrFile('known', labels: ['7'])
    dxrFile('new', labels: ['7'])

    def run = rerun(delegate, s.q, options: [importDecision: false, keepInSync: false, autoImportNewFiles: true], expect: 'waiting')

    assert fileAssets() == [known]
    assert linked(s.q, 'returns', known)
    assert run.var('importSkippedNewCount') == 1
    assert fx.assets[s.q].tags.isEmpty()                     // this run only: no tag written
}

test(variant: 'edge', 'RERUN-OPT-3: the options change the sync filter\'s tags, both ways') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-keep-in-sync']])
    dxrFile('f1', labels: ['7'])

    rerun(delegate, s.q, options: [importDecision: true, keepInSync: false, autoImportNewFiles: false], expect: 'waiting')
    assert fx.assets[s.q].tags as Set == ['dataxray-no-auto-import'] as Set
    assert attr(assetIdOf('f1'), 'syncMode') == 'One-time import'

    rerun(delegate, s.q, options: [importDecision: true, keepInSync: true, autoImportNewFiles: true], expect: 'waiting')
    assert fx.assets[s.q].tags as Set == ['dataxray-keep-in-sync'] as Set
    assert attr(assetIdOf('f1'), 'syncMode') == 'Kept in sync nightly'
}

test(variant: 'edge', 'RERUN-OPT-4: a headless rerun asks nothing, makes no count request and follows the tags') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-keep-in-sync', 'dataxray-no-auto-import']])
    def known = fileAsset('known')
    dxrFile('known', labels: ['7'])
    dxrFile('new', labels: ['7'])

    def run = rerun(delegate, s.q, headless: true, expect: 'completed')

    assert run.tasks.isEmpty()
    assert dxr.searchRequests().every { it.body.pageSize != 1 }
    assert fileAssets() == [known]
    assert run.var('importSkippedNewCount') == 1
    assert fx.assets[s.q].tags as Set == ['dataxray-keep-in-sync', 'dataxray-no-auto-import'] as Set
    assert attr(known, 'syncMode') == 'Kept in sync nightly'
}

test(variant: 'edge', 'RERUN-OPT-5: DataXray unreachable while counting: no options task, the summary says why') {
    def s = contactsSearch(delegate)
    def known = fileAsset('known')
    link(s.q, 'returns', known)
    dxrFile('known', labels: ['7'])
    3.times { dxr.failNext([error: 'IO exception occurred']) }

    def run = rerun(delegate, s.q, expect: 'waiting')

    assert run.task.formKey == 'rerunSummaryForm'
    assert run.task.missingFormVariables == []
    assert run.tasks*.formKey == ['rerunSummaryForm']
    assert run.var('rerunAborted') == true
    assert run.var('rerunAbortReason').contains('Could not reach DataXray')
    assert linked(s.q, 'returns', known) && status(known) == 'Candidate'   // nothing changed
}

test(variant: 'edge', 'RERUN-OPT-5: a count request that fails once is retried') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxr.failNext([error: 'IO exception occurred'])

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')

    assert run.task.formKey == 'rerunOptionsForm'
    assert run.var('rerunResultCountDisplay') == '1'
}

// File assets that fill Collibra up to `total` (the population the limits count).
def fillFileAssets(fake.World world, int total) {
    int have = world.fileAssets().size()
    (have..<total).each { n ->
        world.fx.insertAsset(name: "filler-${n}".toString(), domainId: world.ids.filesDomainId,
                             typeId: world.ids.fileTypeId, statusId: fake.World.CANDIDATE)
    }
}

test(variant: 'edge', 'RERUN-OPT-6: the limits are checked against the population when the options are sent, not when the task opened') {
    def s = contactsSearch(delegate)
    dxrFile('f1', labels: ['7'])
    dxrFile('f2', labels: ['7'])

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')
    assert run.var('importAllowed') == true
    fillFileAssets(delegate, 24_999)                          // another import fills Collibra meanwhile

    def refused = complete(run, [importDecision: true, keepInSync: true, autoImportNewFiles: true])

    assert refused.state == 'error'
    assert refused.error.message.contains('25,000 instance-wide limit')
    assert fileAssets().size() == 24_999
    assert fx.assets[s.q].tags.isEmpty()                     // refused before any option was written
    assert attr(s.q, 'query') == null
}

test(variant: 'edge', 'RERUN-OPT-7: on Cloud + Edge the new-file count is a lower bound, and creating stops at the limit') {
    def s = contactsSearch(delegate)
    ['old1', 'old2'].each { link(s.q, 'returns', fileAsset(it)) }
    dxrFile('new1', labels: ['7'])                             // the old files left the results,
    dxrFile('new2', labels: ['7'])                             // two new ones took their place
    fillFileAssets(delegate, 24_999)

    def run = rerun(delegate, s.q, atOptions: true, expect: 'waiting')
    assert run.var('rerunNewFilesDisplay') == 'at least 0'
    assert run.var('importAllowed') == true

    run = complete(run, [importDecision: true, keepInSync: false, autoImportNewFiles: true])

    assert run.state == 'waiting' && run.task.formKey == 'rerunSummaryForm'
    assert run.task.missingFormVariables == []
    assert fileAssets().size() == 25_000                      // one created, the cap holds
    assert run.var('importCreatedCount') == 1
    assert run.var('importCapped') == true && run.var('importCappedCount') == 1
    assert run.var('importSkippedNewCount') == 1
}

test(variant: 'edge', 'RERUN-OPT-8: switching keep in sync off updates DataXray Sync even when the rerun returns 0 results') {
    def s = contactsSearch(delegate, query: [tags: ['dataxray-keep-in-sync']])
    def known = fileAsset('known')
    link(s.q, 'returns', known)
    fx.setAttributeValues(known, ids.syncModeAttrTypeId, ['Kept in sync nightly'])

    def run = rerun(delegate, s.q, options: [importDecision: false, keepInSync: false, autoImportNewFiles: true], expect: 'waiting')

    assert run.var('rerunRetireSkipped') == true             // 0 results: the links stay
    assert linked(s.q, 'returns', known)
    assert attr(known, 'syncMode') == 'One-time import'
}

// --- Extracted value (v2.12) -----------------------------------------------------------------------

test(variant: 'edge', 'a rerun rebuilds the extracted-value filter from the sync filter (VAL-5)') {
    def pii = classification('Contains PII?', 'extractor', indexId: '50', dataType: 'TEXT')
    def q = query('PII yes', valueFilter: '*es', valueFilterExtractors: [pii])
    dxrFile('yes1', extractors: ['50': 'Yes'])
    dxrFile('no1', extractors: ['50': 'No'])

    rerun(delegate, q, expect: 'waiting')

    assert fileAssets() == [assetIdOf('yes1')]
    def search = dxr.requests.find { it.path == '/api/indexed-files/search' }
    assert search.body.filter.query_items.collect { [it.parameter, it.match_strategy, it.value] } ==
        [['extracted_metadata#50', 'endsWith', 'es']]
}

test(variant: 'edge', 'a rerun whose extracted-value extractor was deleted in DataXray is refused, naming it (VAL-5)') {
    def pii = classification('Contains PII?', 'extractor', indexId: '50', status: fx.OBSOLETE)
    def q = query('PII yes', valueFilter: 'yes', valueFilterExtractors: [pii])

    def run = rerun(delegate, q, expect: 'error')

    assert run.error.userMessage.contains("extracted-value extractor 'Contains PII?' was deleted in DataXray")
    assert dxr.requests.isEmpty()
}
