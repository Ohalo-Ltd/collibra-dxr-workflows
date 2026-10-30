// exists_prepare_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs right after the nightly driver (fan-out + orphan sweep): lists the file
// assets no search returns ('Not in any search', 'Not in DataXray'), groups
// them by datasource into batches and arms the first page of the first batch's
// existence check (shared/file_exists_edge.groovy, mode 'nightly'). Synchronous
// and timer-started, so it NEVER throws: any failure logs and ends the run
// (hasMoreWork false).
//
// Process variables produced (audit only): the exists* counters listed in
// shared/file_exists_edge.groovy.

// {{include:file_exists_edge.groovy}}

prepareExistsCheck(dxrModelIds(), [mode: 'nightly', assetIds: null, label: 'DataXray existence check'])
