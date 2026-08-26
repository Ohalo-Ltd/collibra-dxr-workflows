// rerun_collector.groovy
//
// First stage of "Rerun Data X-Ray Search" — an ASSET-scoped workflow started
// from a saved Unstructured Data Query asset (or headlessly, per query, by the
// nightly file sync driver).
//
// The saved criteria are NOT a frozen query string: the query is REBUILT from
//   – the query asset's "groups" relations to classification assets, using
//     their CURRENT names (so a Data X-Ray-side rename never breaks a saved
//     search — the classification sync keeps those names up to date), and
//   – the "Annotated Text Filter" attribute stored at search time, scoped to the
//     annotators linked by the query's "searches text in" relations.
// The composed string is written back to the "Data X-Ray Query" attribute as a
// record of what actually ran.
//
// Classification lifecycle:
//   – renamed in Data X-Ray → handled automatically (rebuilt from current names)
//   – deleted in Data X-Ray → its Collibra asset is Obsolete (the sync retires,
//     never deletes). Rerunning a query with a retired criterion FAILS with a
//     message naming it — silently dropping an AND criterion would broaden the
//     search and import files the user never asked for. Headless runs log the
//     reason, touch nothing, and end cleanly.
//
// Headless mode: the nightly driver starts this workflow with the hidden
// 'headless' form property set to 'true'. Headless runs never throw (they are
// unattended) and skip the summary user task via a gateway; every abort path
// here sets rerunAborted=true so the downstream batch/retire tasks no-op.
//
// Produces the same batched work-list variables as the search workflow's
// import collector (importWorkList/importTotal/importCursor/hasMoreWork and
// the import*Count counters — sync_files_batch.groovy is a twin of
// import_batch.groovy), plus:
//   previousFileIds (String)  – JSON array of file-asset UUIDs the query
//                               currently "returns" (retire pass diffs these)
//   rerunAborted    (Boolean) – true when the rerun could not run; batch and
//                               retire tasks then do nothing
//   rerunAbortReason(String)  – human-readable abort reason ('' when fine)
//   conditionName   (String)  – the query asset's name (for the summary form)
//   queryAssetId    (String)  – the query asset's UUID
//   rerunRetiredCount / rerunUnlinkedCount (Integer) – zeroed here, advanced
//                               by retire_orphans.groovy
//
// NOTE: keep the Data X-Ray row-extraction helpers below in sync with
// workflows/search-data-xray/scripts/import_collector.groovy.

import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest
import com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest
import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

// --- Constants (canonical operating-model IDs; see configure workflow) -------

def classificationsDomainId = string2Uuid('019c9fbf-622c-76f4-9dd6-2a9730a11515')
def filesDomainId           = string2Uuid('019e9210-52a4-7c31-9b5e-3d8f0a6c1e42')
def groupsRelationTypeId    = string2Uuid('00000000-0000-0000-0000-000000007017')
def returnsRelationTypeId   = string2Uuid('019e9210-f180-79dc-b5a0-6c31e94f82d5')
def dataxrayIdAttrTypeId    = string2Uuid('019e73ae-1aa8-700c-8086-626326822c22')
def queryAttrTypeId         = string2Uuid('019e9210-cf9b-751a-85f2-d30c7a48b9e6')
def filterAttrTypeId        = string2Uuid('019e9210-e04d-7c6b-a481-5f29d8036c7a')
def textFilterRelTypeId     = string2Uuid('019e9210-f2a1-7d3e-8c4b-5a6f7e8d9c01')  // searches text in (query -> Annotator)

def LABEL_TYPE_ID     = string2Uuid('019c9fbe-25c3-71b7-90ac-057dd582fa1e')
def EXTRACTOR_TYPE_ID = string2Uuid('019c9fbd-a91b-7242-9451-79ab632163a3')
def ANNOTATOR_TYPE_ID = string2Uuid('01922a69-e7a0-7ac7-a581-c9ba9286ccf1')
def OBSOLETE_STATUS_ID = string2Uuid('00000000-0000-0000-0000-000000005011')

def MAX_TOTAL_FILE_ASSETS = 25_000
def BATCH_SIZE            = 50

// --- Headless normalization ----------------------------------------------------

// 'headless' arrives as a hidden form property ('true' when the nightly driver
// starts this workflow). Normalize it to a plain string so the BPMN gateway
// can test ${headless == 'true'} regardless of how it arrived.
boolean headless = (execution.getVariable('headless') ?: 'false').toString().trim() == 'true'
execution.setVariable('headless', headless ? 'true' : 'false')

// Every abort path funnels through here: interactive runs throw (the user sees
// why), headless runs record the reason and end cleanly (a nightly worker must
// never wedge — the batch/retire tasks check rerunAborted and no-op).
def abort = { String title, String message ->
    loggerApi.error("Rerun Data X-Ray Search aborted: ${message}")
    execution.setVariable('rerunAborted', true)
    execution.setVariable('rerunAbortReason', message)
    execution.setVariable('importWorkList', '')
    execution.setVariable('importTotal', 0)
    execution.setVariable('importCursor', 0)
    execution.setVariable('hasMoreWork', false)
    execution.setVariable('importBatchCount', 0)
    execution.setVariable('importCreatedCount', 0)
    execution.setVariable('importUpdatedCount', 0)
    execution.setVariable('importFailedCount', 0)
    execution.setVariable('importRelationCount', 0)
    execution.setVariable('importRelationsRemovedCount', 0)
    execution.setVariable('importSkippedClassifications', 0)
    execution.setVariable('previousFileIds', '[]')
    execution.setVariable('rerunRetiredCount', 0)
    execution.setVariable('rerunUnlinkedCount', 0)
    if (!headless) {
        def wf = new WorkflowException(message)
        wf.setTitleMessage(title)
        wf.setUserMessage(message)
        throw wf
    }
}

// --- Config -----------------------------------------------------------------------

def isPlaceholder = { String s -> s.startsWith('<paste ') && s.endsWith('>') }
def dataxrayUrl       = (execution.getVariable('dataxrayUrl') ?: '').toString().trim().replaceAll('/+$', '')
def dataxrayAuthToken = (execution.getVariable('dataxrayAuthToken') ?: '').toString().trim()
if (dataxrayUrl.isEmpty() || isPlaceholder(dataxrayUrl) || dataxrayAuthToken.isEmpty() || isPlaceholder(dataxrayAuthToken)) {
    abort('Rerun Data X-Ray Search misconfigured',
        'The Data X-Ray Base URL / Bearer token are not set on this workflow. Open its settings page and provide them.')
    return
}

// --- The query asset ---------------------------------------------------------------

def queryId = item?.getId()
if (queryId == null) {
    abort('Rerun Data X-Ray Search', 'No query asset — this workflow must be started from an Unstructured Data Query asset.')
    return
}
def queryAsset = assetApi.getAsset(queryId)

// Hard type guard: this workflow is offered on EVERY asset page (Collibra
// can't scope a workflow with global start roles to one asset type —
// asset-type assignment rules are rejected with workflowWrongRoles). Started
// on anything but an Unstructured Data Query it must stop HERE: a file asset
// also carries "groups" relations to classifications, and without this guard
// the rebuild below would happily treat them as search criteria and import
// on the file's behalf.
def QUERY_TYPE_ID = string2Uuid('019dcf97-3bac-72c3-8b59-b6ddbe8a8396')
if (queryAsset.getType()?.getId() != QUERY_TYPE_ID) {
    abort('Rerun Data X-Ray Search',
        "'${queryAsset.getName()}' is a ${queryAsset.getType()?.getName()} asset — this workflow can only rerun an Unstructured Data Query asset (a saved search created by Search Data X-Ray).")
    return
}
def conditionName = queryAsset.getName()
execution.setVariable('conditionName', conditionName)
execution.setVariable('queryAssetId', queryId.toString())

// --- Rebuild the criteria from relations + the stored filter -------------------------

def labelNames = []
def extractorNames = []
def annotatorNames = []
def deadCriteria = []
def ignoredCriteria = 0

def relCursor = ''
while (true) {
    def page = relationApi.findRelations(FindRelationsRequest.builder()
        .relationTypeId(groupsRelationTypeId)
        .sourceId(queryId)
        .limit(1000)
        .cursor(relCursor)
        .build())
    page.getResults().each { rel ->
        def targetId = rel.getTarget().getId()
        def target
        try {
            target = assetApi.getAsset(targetId)
        } catch (Exception goneEx) {
            deadCriteria << "criterion asset ${targetId} no longer exists"
            return
        }
        if (target.getStatus()?.getId() == OBSOLETE_STATUS_ID) {
            deadCriteria << "'${target.getName()}' was deleted in Data X-Ray (its Collibra asset is retired)"
            return
        }
        def typeId = target.getType()?.getId()
        if (typeId == LABEL_TYPE_ID) { labelNames << target.getName() }
        else if (typeId == EXTRACTOR_TYPE_ID) { extractorNames << target.getName() }
        else if (typeId == ANNOTATOR_TYPE_ID) { annotatorNames << target.getName() }
        else { ignoredCriteria++ }
    }
    relCursor = page.getNextCursor()
    if (!relCursor) break
}
if (ignoredCriteria > 0) {
    loggerApi.warn("Rerun of '${conditionName}': ignoring ${ignoredCriteria} related asset(s) that are not Label/Extractor/Annotator classifications")
}

if (!deadCriteria.isEmpty()) {
    abort('Rerun Data X-Ray Search — criterion no longer exists',
        "Cannot rerun '${conditionName}': ${deadCriteria.join('; ')}. Rerunning without it would broaden the search and import files you never asked for. Recreate the classification in Data X-Ray (and run Sync Data X-Ray Classifications), or create a new search.")
    return
}

def filter = readSingleAttribute(queryId, filterAttrTypeId)

// Annotators the annotated-text filter is scoped to ("searches text in" relations).
// Same liveness rule as the criteria: a retired/missing one aborts the rerun.
// Only consulted when a phrase is actually stored — without one the relations
// are inert (the search never wrote them, and the phrase clause isn't emitted),
// so a stale relation must not be able to block a rerun.
def filterAnnotatorNames = []
def filterCursor = ''
while (!filter.isEmpty()) {
    def page = relationApi.findRelations(FindRelationsRequest.builder()
        .relationTypeId(textFilterRelTypeId)
        .sourceId(queryId)
        .limit(1000)
        .cursor(filterCursor)
        .build())
    page.getResults().each { rel ->
        def targetId = rel.getTarget().getId()
        def target
        try {
            target = assetApi.getAsset(targetId)
        } catch (Exception goneEx) {
            deadCriteria << "annotated-text annotator ${targetId} no longer exists"
            return
        }
        if (target.getStatus()?.getId() == OBSOLETE_STATUS_ID) {
            deadCriteria << "annotated-text annotator '${target.getName()}' was deleted in Data X-Ray (its Collibra asset is retired)"
            return
        }
        filterAnnotatorNames << target.getName()
    }
    filterCursor = page.getNextCursor()
    if (!filterCursor || !deadCriteria.isEmpty()) break
}
if (!deadCriteria.isEmpty()) {
    abort('Rerun Data X-Ray Search — criterion no longer exists',
        "Cannot rerun '${conditionName}': ${deadCriteria.join('; ')}. Rerunning without it would broaden the search and import files you never asked for. Recreate the classification in Data X-Ray (and run Sync Data X-Ray Classifications), or create a new search.")
    return
}

if (labelNames.isEmpty() && extractorNames.isEmpty() && annotatorNames.isEmpty() && filter.isEmpty()) {
    abort('Rerun Data X-Ray Search — no saved criteria',
        "Cannot rerun '${conditionName}': it has no linked classifications and no annotated-text filter, so its criteria cannot be reconstructed. Create a new search instead.")
    return
}

// --- Compose the query (same composition rules as search_data_xray.groovy) ----------

def clauses = []
appendNameClause(clauses, 'labels.name', labelNames)
appendNameClause(clauses, 'extractedMetadata.name', extractorNames)
appendNameClause(clauses, 'annotators.name', annotatorNames)
if (!filter.isEmpty()) {
    clauses << buildAnnotatorPhraseClause(filterAnnotatorNames, filter)
}
def queryString = clauses.join(' AND ')
loggerApi.info("Rerun of '${conditionName}' — rebuilt Data X-Ray query: ${queryString ?: '(all files)'}")

// Record what actually ran (replace-all, so the attribute tracks the latest run).
try {
    assetApi.setAssetAttributes(SetAssetAttributesRequest.builder()
        .assetId(queryId)
        .typeId(queryAttrTypeId)
        .values([queryString ?: '(all files)'] as List<Object>)
        .build())
} catch (Exception attrEx) {
    loggerApi.warn("Could not update the Data X-Ray Query attribute on ${queryId}: ${attrEx.message}")
}

// --- The files this query currently returns (for the retire diff) --------------------

def previousFileIds = [] as Set
relCursor = ''
while (true) {
    def page = relationApi.findRelations(FindRelationsRequest.builder()
        .relationTypeId(returnsRelationTypeId)
        .sourceId(queryId)
        .limit(1000)
        .cursor(relCursor)
        .build())
    page.getResults().each { rel -> previousFileIds << rel.getTarget().getId().toString() }
    relCursor = page.getNextCursor()
    if (!relCursor) break
}

// --- Re-run the query against Data X-Ray ----------------------------------------------

def encodedQuery = URLEncoder.encode(queryString, StandardCharsets.UTF_8.toString())
def searchUrl = "${dataxrayUrl}/api/v1/files?q=${encodedQuery}"

// Data X-Ray can transiently stall an NDJSON stream mid-response — retry a
// fresh attempt before giving up (see search_data_xray.groovy).
def FETCH_ATTEMPTS = 3
def workItems = []
def skippedNoId = 0
fetchLoop:
for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
    workItems = []
    skippedNoId = 0
    try {
    def conn = (HttpURLConnection) new URL(searchUrl).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${dataxrayAuthToken}")
    conn.setRequestProperty('Content-Type', 'application/json')
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(120_000)  // between-bytes: a 2-min silence = stalled stream, retry

    def code = conn.getResponseCode()
    if (code != 200) {
        def errBody = ''
        try { errBody = conn.getErrorStream()?.getText('UTF-8') ?: '' } catch (Exception ignored) { /* best-effort */ }
        throw new RuntimeException("Data X-Ray API returned HTTP ${code}: ${errBody.take(500)}")
    }

    def input = conn.getInputStream()
    try {
        def reader = new ObjectMapper().readerFor(Map).readValues(input)
        while (reader.hasNextValue()) {
            def row = reader.nextValue()
            def tuple = extractFileTuple(row, dataxrayUrl)
            if (tuple == null) { skippedNoId++ } else { workItems << tuple }
        }
    } finally {
        try { input.close() } catch (Exception ignored) { /* best-effort */ }
        conn.disconnect()
    }
    break fetchLoop
    } catch (Exception fetchEx) {
        if (attempt < FETCH_ATTEMPTS) {
            loggerApi.warn("Data X-Ray fetch attempt ${attempt}/${FETCH_ATTEMPTS} failed (${fetchEx.message}) — retrying")
            sleep(5000)
        } else {
            abort('Rerun Data X-Ray Search failed',
                "Could not fetch results from Data X-Ray for '${conditionName}' (${FETCH_ATTEMPTS} attempts): ${fetchEx.message}")
            return
        }
    }
}
if (skippedNoId > 0) {
    loggerApi.warn("Rerun of '${conditionName}': skipped ${skippedNoId} result row(s) without a usable file id")
}

// --- Instance-wide cap guard -----------------------------------------------------------

// Files this query already returns update in place — only genuinely new files
// grow the domain, so subtract the overlap (exact for THIS query; overlap with
// other queries' files still counts as new, which errs on the safe side).
int alreadyOurs = 0
workItems.each { tuple ->
    if (previousFileIds.contains(deterministicFileAssetId(tuple[0]).toString())) { alreadyOurs++ }
}
int filesDomainCount = countAssetsInDomain(filesDomainId)
int projectedTotal = filesDomainCount + (workItems.size() - alreadyOurs)
if (projectedTotal > MAX_TOTAL_FILE_ASSETS) {
    abort('Rerun Data X-Ray Search refused',
        "Rerun of '${conditionName}' refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this rerun would add ${workItems.size() - alreadyOurs} more — the projected ${projectedTotal} exceeds the ${MAX_TOTAL_FILE_ASSETS} instance-wide limit.")
    return
}

// --- Resolve classifications and store the work list -------------------------------------

def classificationAssets = fetchAllAssetsInDomain(classificationsDomainId)
def classAssetIdByName = [:]
classificationAssets.values().each { a -> classAssetIdByName[a.getName()] = a.getId() }
def classAssetIdByDxrId = fetchAssetIdsByDataxrayId(dataxrayIdAttrTypeId, classificationAssets.keySet())

int unresolvedClassifications = 0
workItems.each { tuple ->
    def resolved = [] as Set
    tuple[7].each { ref ->
        def hit = (ref[0] ? classAssetIdByDxrId[ref[0]] : null) ?: (ref[1] ? classAssetIdByName[ref[1]] : null)
        if (hit) { resolved << hit.toString() } else { unresolvedClassifications++ }
    }
    tuple[7] = resolved as List
}

def json = JsonOutput.toJson(workItems)
def baos = new ByteArrayOutputStream()
def gz = new GZIPOutputStream(baos)
gz.write(json.getBytes(StandardCharsets.UTF_8))
gz.close()
def encoded = Base64.getEncoder().encodeToString(baos.toByteArray())

int batchCount = (int) Math.ceil(workItems.size() / (double) BATCH_SIZE)
execution.setVariable('rerunAborted', false)
execution.setVariable('rerunAbortReason', '')
execution.setVariable('importWorkList', encoded)
execution.setVariable('importTotal', workItems.size())
execution.setVariable('importCursor', 0)
execution.setVariable('hasMoreWork', !workItems.isEmpty())
execution.setVariable('importBatchCount', batchCount)
execution.setVariable('importCreatedCount', 0)
execution.setVariable('importUpdatedCount', 0)
execution.setVariable('importFailedCount', 0)
execution.setVariable('importRelationCount', 0)
execution.setVariable('importRelationsRemovedCount', 0)
execution.setVariable('importSkippedClassifications', unresolvedClassifications)
execution.setVariable('previousFileIds', JsonOutput.toJson(previousFileIds as List))
execution.setVariable('rerunRetiredCount', 0)
execution.setVariable('rerunUnlinkedCount', 0)

loggerApi.info("Rerun of '${conditionName}' ready: ${workItems.size()} file(s) in ${batchCount} batch(es); ${previousFileIds.size()} previously returned, ${alreadyOurs} overlap; files domain holds ${filesDomainCount}")

// --- Helpers (keep in sync with search-data-xray's scripts) -----------------------------

// Escape a value for use inside a double-quoted query term: backslashes and
// double quotes would otherwise terminate the term (Data X-Ray answers HTTP 400
// for an unbalanced quote; "\"" and "\\" are accepted — verified live).
def quoteTerm(String field, Object value) {
    def escaped = value.toString().replace('\\', '\\\\').replace('"', '\\"')
    return "${field}:\"${escaped}\"".toString()
}

// Append one "field:\"value\"" term per selected value. Every term is a separate
// top-level clause, so the final `clauses.join(' AND ')` requires ALL of them.
def appendNameClause(List clauses, String field, List names) {
    def present = names.findAll { it != null && !it.toString().trim().isEmpty() }
    present.each { clauses << quoteTerm(field, it) }
}

// Build the nested annotator clause that ties a "contains" phrase match to the
// annotators picked for the annotated-text filter. These are deliberately OR-ed:
// the phrase must appear in ANY one of them —
//   annotators: { (name:"A" OR name:"C") AND annotations.phrase:"*text*" }
// The nested block keeps phrase and annotator identity on the SAME annotator.
// With no names, scopes to all annotators: annotators: { annotations.phrase:"*text*" }
// (The Annotators criterion itself is AND-ed via appendNameClause, independently.)
def buildAnnotatorPhraseClause(List names, String phrase) {
    def present = names.findAll { it != null && !it.toString().trim().isEmpty() }
    def phraseTerm = quoteTerm('annotations.phrase', "*${phrase}*")
    def inner
    if (present.isEmpty()) {
        inner = phraseTerm
    } else {
        def nameTerms = present.collect { quoteTerm('name', it) }
        def nameClause = nameTerms.size() == 1 ? nameTerms[0] : "(${nameTerms.join(' OR ')})"
        inner = "${nameClause} AND ${phraseTerm}"
    }
    return "annotators: { ${inner} }".toString()
}

def readSingleAttribute(UUID assetId, UUID typeId) {
    try {
        def page = attributeApi.findAttributes(FindAttributesRequest.builder()
            .assetId(assetId)
            .typeIds([typeId])
            .limit(1)
            .build())
        def results = page.getResults()
        return results.isEmpty() ? '' : (results[0].getValue() ?: '').toString().trim()
    } catch (Exception attrEx) {
        loggerApi.warn("Could not read attribute ${typeId} on ${assetId}: ${attrEx.message}")
        return ''
    }
}

def deterministicFileAssetId(fileId) {
    return UUID.nameUUIDFromBytes(("dxr-file:" + fileId).getBytes(StandardCharsets.UTF_8))
}

// Row shape verified against a live /api/v1/files NDJSON response — see the
// documented sample in import_collector.groovy (the twin of this code).
def extractFileTuple(row, String baseUrl) {
    def id = firstNonEmpty(row?.fileId, row?.id)
    if (!id) { return null }
    def datasource = (row?.datasource instanceof Map ? row.datasource.name : row?.datasource) ?: ''
    def path = firstNonEmpty(row?.path, row?.filePath)
    def fileName = firstNonEmpty(row?.fileName, baseNameOf(path.toString()))
    def size = row?.size == null ? '' : row.size
    def modified = firstNonEmpty(row?.lastModifiedAt, row?.modifiedAt, row?.lastModified)
    // File rows carry no UI link field today; keep the extraction so a future
    // Data X-Ray that adds one gets deep links for free. Never fabricate URLs.
    def link = firstNonEmpty(row?.link, row?.url)
    def deepLink = link ? (link.toString().startsWith('http') ? link.toString() : baseUrl + link) : ''
    def classifications = []
    classifications.addAll(extractClassifications(row?.labels))
    classifications.addAll(extractClassifications(row?.extractedMetadata))
    classifications.addAll(extractClassifications(row?.annotators))
    return [id.toString(), datasource.toString(), path.toString(), fileName.toString(),
            size.toString(), modified.toString(), deepLink, classifications]
}

// Keep only entries with positive hit evidence — labels/extractedMetadata by
// presence, annotators via uniquePhrases/annotations (checked-with-zero is
// NOT a match). Shapes documented in import_collector.groovy.
def extractClassifications(entries) {
    def out = []
    (entries instanceof Collection ? entries : []).each { e ->
        if (e instanceof Map) {
            def count = firstNonNull(e.uniquePhrases, e.count, e.hitCount)
            def hitList = e.annotations
            def hasEvidence
            if (count != null) {
                hasEvidence = (count instanceof Number ? count.intValue() : (count.toString().isInteger() ? count.toString().toInteger() : 0)) > 0
            } else if (hitList instanceof Collection) {
                hasEvidence = !hitList.isEmpty()
            } else {
                hasEvidence = true
            }
            if (hasEvidence && (e.id || e.name)) {
                out << [(e.id ?: '').toString(), (e.name ?: '').toString()]
            }
        } else if (e != null) {
            out << ['', e.toString()]
        }
    }
    return out
}

def baseNameOf(String path) {
    def cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return cut >= 0 ? path.substring(cut + 1) : path
}

def firstNonEmpty(Object... values) {
    for (v in values) {
        if (v != null && !v.toString().trim().isEmpty()) { return v }
    }
    return ''
}

def firstNonNull(Object... values) {
    for (v in values) {
        if (v != null) { return v }
    }
    return null
}

def countAssetsInDomain(UUID domainId) {
    int count = 0
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(FindAssetsRequest.builder()
            .domainId(domainId).limit(1000).cursor(cursor).build())
        count += page.getResults().size()
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return count
}

def fetchAllAssetsInDomain(UUID domainId) {
    def byId = [:]
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(FindAssetsRequest.builder()
            .domainId(domainId).limit(1000).cursor(cursor).build())
        page.getResults().each { asset -> byId[asset.getId()] = asset }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return byId
}

def fetchAssetIdsByDataxrayId(UUID attrTypeId, Set<UUID> assetsInDomain) {
    def assetIdByDataxrayId = [:]
    def cursor = ''
    while (true) {
        def page = attributeApi.findAttributes(FindAttributesRequest.builder()
            .typeIds([attrTypeId]).limit(1000).cursor(cursor).build())
        page.getResults().each { attr ->
            def aid = attr.getAsset()?.getId()
            def val = attr.getValue()
            if (aid && val && assetsInDomain.contains(aid)) {
                assetIdByDataxrayId[val.toString()] = aid
            }
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return assetIdByDataxrayId
}
