// What people read on a file asset: size, date, names. R22, R23; FILE-24, FILE-26.

table('readableFileSize (FILE-24)', [
    [case: 'zero', in: '0', out: '0 bytes'],
    [case: 'one byte', in: '1', out: '1 byte'],
    [case: 'under 1 KB', in: '1023', out: '1,023 bytes'],
    [case: '1 KB', in: '1024', out: '1.0 KB (1,024 bytes)'],
    [case: 'the TRC example', in: '27885', out: '27.2 KB (27,885 bytes)'],
    [case: 'megabytes', in: '5242880', out: '5.0 MB (5,242,880 bytes)'],
    [case: 'gigabytes', in: '1610612736', out: '1.5 GB (1,610,612,736 bytes)'],
    [case: 'a number DataXray sends as text with spaces', in: ' 2048 ', out: '2.0 KB (2,048 bytes)'],
    [case: 'not a number is kept as is', in: '27 KB', out: '27 KB'],
    [case: 'nothing', in: null, out: ''],
]) { row -> assert readableFileSize(row.in) == row.out }

table('readableTimestamp (FILE-24)', [
    [case: 'ISO instant', in: '2025-04-10T17:22:31Z', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'fractional seconds', in: '2025-04-10T17:22:31.123456Z', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'an offset is converted to UTC', in: '2025-04-10T19:22:00+02:00', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'no zone is read as UTC', in: '2025-04-10T17:22:00', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'epoch seconds', in: '1744305720', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'epoch milliseconds', in: '1744305720000', out: '10 Apr 2025, 17:22 UTC'],
    [case: 'anything else is kept', in: 'last Tuesday', out: 'last Tuesday'],
    [case: 'nothing', in: '', out: ''],
]) { row -> assert readableTimestamp(row.in) == row.out }

test('fileNameOf: the row name, else the basename of the path, else a placeholder') {
    assert fileNameOf(tuple('f', name: 'Report.pdf')) == 'Report.pdf'
    assert fileNameOf(['f', 'ds', '/a/b/c.txt', '', '', '', '', [], '', '', []]) == 'c.txt'
    assert fileNameOf(['f', 'ds', 'C:\\docs\\d.docx', '', '', '', '', [], '', '', []]) == 'd.docx'
    assert fileNameOf(['f', 'ds', '', '', '', '', '', [], '', '', []]) == '(unnamed file)'
    assert fileNameOf(tuple('f', name: 'x' * 300)).length() == 240
}

test('uniqueAssetName: a short suffix from the asset id keeps repeated file names apart') {
    def a = deterministicFileAssetId('one')
    def b = deterministicFileAssetId('two')
    assert uniqueAssetName('invoice.pdf', a) != uniqueAssetName('invoice.pdf', b)
    assert uniqueAssetName('invoice.pdf', a) ==~ /invoice\.pdf \[[0-9a-f]{8}\]/
    assert uniqueAssetName('y' * 300, a).startsWith('y' * 200 + ' [')
}

test('deterministicFileAssetId: one DataXray file id, one asset id, on every instance (R3)') {
    assert deterministicFileAssetId('abc') == deterministicFileAssetId('abc')
    assert deterministicFileAssetId('abc') != deterministicFileAssetId('abd')
    assert deterministicFileAssetId('abc') == UUID.nameUUIDFromBytes('dxr-file:abc'.getBytes('UTF-8'))
}

table('fileObjectKey: datasource id + object id, both or nothing (R1)', [
    [case: 'both', t: ['f', '', '', '', '', '', '', [], '312', 'Shared/1/7', []], out: '312:Shared/1/7'],
    [case: 'object id only', t: ['f', '', '', '', '', '', '', [], '', 'Shared/1/7', []], out: null],
    [case: 'datasource only', t: ['f', '', '', '', '', '', '', [], '312', '', []], out: null],
    [case: 'a short on-prem-era tuple', t: ['f', '', '', '', '', '', '', []], out: null],
]) { row -> assert fileObjectKey(row.t) == row.out }

table('extractor values are bounded per edition (v2.8)', [
    [case: 'on-prem', variant: 'onprem', perValue: 2_000, perFile: 50_000],
    [case: 'Cloud + Edge', variant: 'edge', perValue: 500, perFile: 10_000],
]) { row ->
    if (variant != row.variant) { return }
    assert dxrExtractedValueMaxChars() == row.perValue
    assert dxrExtractedValuesMaxChars() == row.perFile
    def long1 = boundExtractedValues([['Notes', 'x' * (row.perValue + 50)]])
    assert long1[0][1].length() == row.perValue + 1                    // cut, with an ellipsis
    def many = (1..200).collect { ['Notes', "${it}".padLeft(4, '0') + 'v' * 400] }
    def bounded = boundExtractedValues(many)
    assert bounded.last()[0] == '…' && bounded.last()[1] ==~ /\d+ more value\(s\) not shown/
}

test('extractor values: sorted, duplicates dropped, rendered as an escaped table') {
    def b = boundExtractedValues([['b', '2'], ['a', '1'], ['b', '2'], ['a', '0']])
    assert b == [['a', '0'], ['a', '1'], ['b', '2']]
    def html = renderExtractedValuesHtml([['IBAN', '<script>x</script> & co']])
    assert html.contains('&lt;script&gt;') && html.contains('&amp; co') && !html.contains('<script>')
    assert renderExtractedValuesHtml([]) == ''
}
