// retire_orphans.groovy
//
// Final sync stage of "Rerun Data X-Ray Search": files the query previously
// returned but no longer does are unlinked and — when NO other query still
// returns them — RETIRED (status Obsolete). Never deleted: comments,
// attachments, workflow tasks and other activities tied to the asset must
// survive as records. If the file later reappears in any query's results, the
// batch stage reactivates it (status Candidate).
//
// Safety rails:
//   – An aborted rerun (rerunAborted=true) touches nothing.
//   – A rerun that returned ZERO results skips retirement entirely — a
//     transient Data X-Ray outage or truncated response must not retire a
//     query's whole file population (same guard as the classification sync).
//   – A file returned by two queries is only retired once BOTH drop it: the
//     query→file "returns" relation is removed first, then the asset is
//     retired only if no "returns" relations remain from any query.
//
// Reads:  rerunAborted, importTotal, importWorkList, previousFileIds, queryAssetId
// Writes: rerunRetiredCount, rerunUnlinkedCount

import com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest
import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

def returnsRelationTypeId = string2Uuid('019e9210-f180-79dc-b5a0-6c31e94f82d5')
def OBSOLETE_STATUS_ID    = string2Uuid('00000000-0000-0000-0000-000000005011')

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
    currentIds << UUID.nameUUIDFromBytes(("dxr-file:" + tuple[0]).getBytes(StandardCharsets.UTF_8)).toString()
}

def previousIds = new JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List
def orphanIds = previousIds.findAll { !currentIds.contains(it.toString()) }

int unlinked = 0
int retired = 0

if (!orphanIds.isEmpty()) {
    // Map file-asset id → relation id for this query's "returns" relations, so
    // each orphan's relation can be removed by id.
    def relationIdByTarget = [:]
    def cursor = ''
    while (true) {
        def page = relationApi.findRelations(FindRelationsRequest.builder()
            .relationTypeId(returnsRelationTypeId)
            .sourceId(queryAssetId)
            .limit(1000)
            .cursor(cursor)
            .build())
        page.getResults().each { rel -> relationIdByTarget[rel.getTarget().getId().toString()] = rel.getId() }
        cursor = page.getNextCursor()
        if (!cursor) break
    }

    orphanIds.each { orphan ->
        def fileAssetId = string2Uuid(orphan.toString())
        try {
            def relId = relationIdByTarget[orphan.toString()]
            if (relId != null) {
                relationApi.removeRelation(relId)
                unlinked++
            }
            // Retire only when no query at all still returns this file.
            def stillReturned = relationApi.findRelations(FindRelationsRequest.builder()
                .relationTypeId(returnsRelationTypeId)
                .targetId(fileAssetId)
                .limit(1)
                .build())
            if (stillReturned.getResults().isEmpty()) {
                def current = assetApi.getAsset(fileAssetId)
                if (current.getStatus()?.getId() != OBSOLETE_STATUS_ID) {
                    assetApi.changeAsset(ChangeAssetRequest.builder()
                        .id(fileAssetId)
                        .statusId(OBSOLETE_STATUS_ID)
                        .build())
                }
                retired++
                loggerApi.info("Retired file asset '${current.getName()}' [${fileAssetId}] — no query returns it any more")
            }
        } catch (Exception orphanEx) {
            loggerApi.error("Failed to unlink/retire orphan file asset ${fileAssetId}: ${orphanEx.message}")
        }
    }
}

execution.setVariable('rerunRetiredCount', retired)
execution.setVariable('rerunUnlinkedCount', unlinked)
loggerApi.info("Retire pass complete: ${unlinked} file(s) unlinked from the query, ${retired} retired (no longer returned by any query)")

// --- Helpers ---------------------------------------------------------------------

def decodeWorkList(String encoded) {
    if (encoded.isEmpty()) { return [] }
    def bytes = Base64.getDecoder().decode(encoded)
    def gz = new GZIPInputStream(new ByteArrayInputStream(bytes))
    def json = new String(gz.readAllBytes(), StandardCharsets.UTF_8)
    return new JsonSlurper().parseText(json)
}
