// rerun_exists_prepare_edge.groovy  (Collibra Cloud + Edge variant)
//
// Runs after the retire pass: asks DataXray about the files this rerun
// stopped returning (or, after a 0-results answer, about every file the
// search returned before) — shared/rerun_exists_edge.groovy. Synchronous;
// never throws: anything that cannot be checked is left to the nightly file
// sync and said so on the summary.

// {{include:rerun_exists_edge.groovy}}

startRerunExistsCheck(dxrModelIds())
