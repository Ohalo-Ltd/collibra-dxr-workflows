// retire_orphans.groovy  (on-prem variant)
//
// Final sync stage of "Rerun Data X-Ray Search": files the query previously
// returned but no longer does are unlinked and — when NO other query still
// returns them — RETIRED (status Obsolete). Never deleted (see
// shared/file_batch.groovy: retireOrphanFiles).
//
// Safety rails:
//   – An aborted rerun (rerunAborted=true) touches nothing.
//   – A rerun that returned ZERO results skips retirement entirely — a
//     transient Data X-Ray outage or truncated response must not retire a
//     query's whole file population (same guard as the classification sync).
//   – A file returned by two queries is only retired once BOTH drop it.
//
// Reads:  rerunAborted, importTotal, importWorkList, previousFileIds, queryAssetId
// Writes: rerunRetiredCount, rerunUnlinkedCount

import groovy.json.JsonSlurper

// {{include:dxr_model.groovy}}
// {{include:worklist.groovy}}
// {{include:file_batch.groovy}}

if (execution.getVariable('rerunAborted') == true) {
    loggerApi.info('Retire pass skipped: the rerun was aborted')
    return
}

int total = (execution.getVariable('importTotal') ?: 0) as int
if (total == 0) {
    loggerApi.warn('Retire pass skipped: the rerun returned 0 results — not retiring anything in case Data X-Ray answered incompletely')
    return
}

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())

// Current result set → deterministic asset ids.
def currentIds = [] as Set
decodeWorkList((execution.getVariable('importWorkList') ?: '').toString()).each { tuple ->
    currentIds << deterministicFileAssetId(tuple[0]).toString()
}
def previousIds = new JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List

def r = retireOrphanFiles(currentIds, previousIds, queryAssetId)

execution.setVariable('rerunRetiredCount', r.retired)
execution.setVariable('rerunUnlinkedCount', r.unlinked)
loggerApi.info("Retire pass complete: ${r.unlinked} file(s) unlinked from the query, ${r.retired} retired (no longer returned by any query)")
