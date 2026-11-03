// Import limits and configuration checks. R12, R13, R21; IMP-4, IMP-5, IMP-13.

table('computeImportFeasibility at the limits', [
    [case: 'nothing matched', total: 0, have: 0, allowed: false, warn: false, blocked: false],
    [case: 'small', total: 10, have: 0, allowed: true, warn: false, blocked: false],
    [case: 'at the warning line', total: 5_000, have: 0, allowed: true, warn: false, blocked: false],
    [case: 'above the warning line (IMP-5)', total: 5_001, have: 0, allowed: true, warn: true, blocked: false],
    [case: 'at the per-search limit', total: 10_000, have: 0, allowed: true, warn: true, blocked: false],
    [case: 'above the per-search limit (IMP-13)', total: 10_001, have: 0, allowed: false, warn: false, blocked: true, msg: 'more than the 10,000 a search can import'],
    [case: 'exactly fills the instance (25,000)', total: 1_000, have: 24_000, allowed: true, warn: false, blocked: false],
    [case: 'would exceed the instance (IMP-4)', total: 1_000, have: 24_001, allowed: false, warn: false, blocked: true, msg: 'the projected 25001 would exceed the 25000 instance-wide limit'],
]) { row ->
    def r = computeImportFeasibility(row.total, row.have, row.total.toString())
    assert [r.importAllowed, r.importWarn, r.importBlocked] == [row.allowed, row.warn, row.blocked]
    if (row.msg) { assert r.importBlockedMessage.contains(row.msg) }
}

test('the limits agreed with TRC') {
    assert dxrMaxTotalFileAssets() == 25_000
    assert dxrMaxImportFiles() == 10_000
    assert dxrWarnImportFiles() == 5_000
    assert dxrEdgeDefaultPageSize() == 25 && dxrEdgeMaxPageSize() == 100
    assert dxrExistsCheckBatchSize() == 200
}

table('edgePageSize clamps the configured page size', [
    [case: 'default when unset', in: null, out: 25],
    [case: 'not a number', in: 'lots', out: 25],
    [case: 'too small', in: '0', out: 1],
    [case: 'too large', in: '500', out: 100],
    [case: 'fine', in: ' 40 ', out: 40],
]) { row -> assert edgePageSize(row.in) == row.out }

test('configuration placeholders count as unset') {
    assert isPlaceholderValue('<paste DataXray Bearer token here>')
    assert !isPlaceholderValue('eyJhbGciOi...')
    vars.dataxrayUrl = '<paste DataXray base URL here>'
    vars.dataxrayAuthToken = 'token'
    def r = readRequiredConfig([dataxrayUrl: 'DataXray Base URL', dataxrayAuthToken: 'Bearer token'])
    assert r.config == [dataxrayAuthToken: 'token']
    assert r.missing == ['DataXray Base URL  (variable: dataxrayUrl)']
    assert normalizeBaseUrl(' https://dxr.example/// ') == 'https://dxr.example'
}

// --- Rerun DataXray Search's settings come from Search DataXray (v2.10.1, ADM-10) ---------

test('adoptSearchDataXraySettings copies the URL and token (on-prem) / connection (Edge) set on Search DataXray') {
    settings('searchDataXray', [dataxrayUrl: 'https://dxr.example.com', dataxrayAuthToken: 'prod-token',
                                dataxrayConnectionName: 'dataxray-production', dataxrayPageSize: '40'])
    def names = ['dataxrayUrl', 'dataxrayAuthToken', 'dataxrayConnectionName', 'dataxrayPageSize']

    def r = adoptSearchDataXraySettings(names)

    assert r == [ok: true, error: '']
    assert names.collect { vars[it] } == ['https://dxr.example.com', 'prod-token', 'dataxray-production', '40']
}

test('adoptSearchDataXraySettings keeps a value passed when the run was started') {
    settings('searchDataXray', [dataxrayUrl: 'https://dxr.example.com', dataxrayAuthToken: 'prod-token'])
    vars.dataxrayAuthToken = 'start-time-token'

    adoptSearchDataXraySettings(['dataxrayUrl', 'dataxrayAuthToken'])

    assert vars.dataxrayAuthToken == 'start-time-token'
    assert vars.dataxrayUrl == 'https://dxr.example.com'
}

table('adoptSearchDataXraySettings replaces a blank or placeholder value', [
    [case: 'blank', own: ''],
    [case: 'spaces only', own: '   '],
    [case: 'placeholder', own: '<paste DataXray Bearer token here>'],
]) { row ->
    settings('searchDataXray', [dataxrayAuthToken: 'prod-token'])
    vars.dataxrayAuthToken = row.own

    adoptSearchDataXraySettings(['dataxrayAuthToken'])

    assert vars.dataxrayAuthToken == 'prod-token'
}

test('adoptSearchDataXraySettings leaves a setting unset when Search DataXray still has its placeholder') {
    // As deployed: Search DataXray's settings are its BPMN defaults until an admin saves them.
    adoptSearchDataXraySettings(['dataxrayUrl'])

    assert readRequiredConfig([dataxrayUrl: 'DataXray Base URL']).missing == ['DataXray Base URL  (variable: dataxrayUrl)']
}

test('adoptSearchDataXraySettings never throws when Search DataXray cannot be read') {
    fx.workflowDefinitions.remove('searchDataXray')

    def r = adoptSearchDataXraySettings(['dataxrayUrl', 'dataxrayAuthToken'])

    assert !r.ok && r.error.contains('not found')
    assert vars.dataxrayUrl == null && vars.dataxrayAuthToken == null
    assert searchSettingsMissingMessage('the DataXray Base URL / Bearer token are', r)
        .contains("Search DataXray's settings could not be read")
}

test('work lists and packed JSON survive the round trip through a process variable') {
    def items = [['f1', 'Ünïcødé', '/a', 'b', '1', '', '', ['x'], '312', 'o', []]]
    assert decodeWorkList(encodeWorkList(items)) == items
    assert decodeWorkList('') == []
    assert unpackJson(packJson([a: [1, 2], b: 'ü'])) == [a: [1, 2], b: 'ü']
}

test('csvEscape follows RFC 4180') {
    assert csvEscape('plain') == 'plain'
    assert csvEscape('a,b') == '"a,b"'
    assert csvEscape('say "hi"') == '"say ""hi"""'
    assert csvEscape(null) == ''
}

test('the version label names the edition under test') {
    assert dxrVersionLabel() == "DataXray workflows test (${variant == 'edge' ? 'cloud-edge' : 'onprem'})"
    assert dxrRunTimestamp() ==~ /\d{1,2} \w{3} \d{4}, \d\d:\d\d UTC/
}
