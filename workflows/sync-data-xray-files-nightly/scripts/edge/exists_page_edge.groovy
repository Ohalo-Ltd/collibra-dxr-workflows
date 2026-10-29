// exists_page_edge.groovy  (Collibra Cloud + Edge variant)
//
// ASYNC task after each existence-check External API task: one page of the
// current batch (shared/file_exists_edge.groovy). When the batch has been read
// to the end, the assets DataXray did not return are set to 'Not in DataXray'
// and the next batch is armed, or the loop ends. Never throws. A failed request
// is retried twice; after that the check stops for tonight — batches already
// applied stay applied, the rest are checked tomorrow.

// {{include:file_exists_edge.groovy}}

handleExistsCheckPage(dxrModelIds(), 'DataXray existence check')
