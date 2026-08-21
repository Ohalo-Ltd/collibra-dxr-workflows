// import_collector.groovy
//
// First stage of the gated asset import: runs synchronously after the user
// accepts "Import all N results as assets?" on the search results form.
//
// Responsibilities:
//   1. Re-check the instance-wide import cap (the form's visibility rules can
//      be bypassed by completing the task via the REST API, so the script is
//      the enforcement point).
//   2. Re-run the Data X-Ray query (streaming NDJSON, exactly like the search
//      task). The preview/import gap can drift a little if Data X-Ray changed
//      in between; the import summary reports what was actually imported.
//   3. Distill every matching file into a compact work-item tuple and store
//      the whole list gzip+Base64-encoded in ONE immutable process variable
//      (importWorkList). The async batch task (import_batch.groovy) then
//      consumes it 50 rows at a time, advancing only an integer cursor — the
//      blob itself is written once, here, and never rewritten.
//   4. Resolve each file's matched classifications to Collibra asset UUIDs up
//      front (by Data X-Ray ID first, then by asset name), so the batch task
//      does no lookups at all.
//   5. Tag the query asset 'dataxray-keep-in-sync' when the user ticked
//      "keep in sync" (the nightly file sync picks flagged queries up).
//
// Work-item tuple (positional, to keep the JSON compact):
//   [0] Data X-Ray file id (String — basis of the deterministic asset UUID)
//   [1] datasource name    (String)
//   [2] full path          (String)
//   [3] file name          (String)
//   [4] size               (String, '' when unknown)
//   [5] last modified      (String, '' when unknown)
//   [6] deep link URL      (String, '' when unknown — file rows currently
//       carry no UI link field)
//   [7] matched classification asset UUIDs (List<String>)
//
// Hit evidence vs "checked with zero": a result row may list a classification
// that was checked but produced no hits — that is NOT a match and must not
// become a relation. extractClassifications() therefore requires positive
// evidence per entry kind. Row shape verified against a live Data X-Ray
// /api/v1/files NDJSON response (demo.dataxray.io, 2026-08):
//   { datasource: {id, name, connector}, fileName, fileId, path, size,
//     mimeType, createdAt, lastModifiedAt, contentSha256, scanDepth,
//     labels: [{id, name}],
//     extractedMetadata: [{id, name, value, type}],
//     annotators: [{id, name, domain, uniquePhrases,
//                   annotations: [{phrase, locations: [{start, end}]}]}],
//     dlpLabels, externalMetadata, entitlements, owner, ... }
// All row-shape assumptions live in extractFileTuple()/extractClassifications().
//
// Process variables produced:
//   importWorkList     (String)  – gzip+Base64 JSON array of work-item tuples
//   importTotal        (Integer) – number of work items
//   importCursor       (Integer) – 0
//   hasMoreWork        (Boolean) – importTotal > 0
//   importCreatedCount / importUpdatedCount / importFailedCount /
//   importRelationCount / importSkippedClassifications (Integer) – zeroed here,
//       advanced by import_batch.groovy
//   importBatchCount   (Integer) – total number of batches (for progress logs)

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetTagsRequest
import com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest
import com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

// --- Constants (canonical operating-model IDs; see configure workflow) -------

def classificationsDomainId = string2Uuid('019c9fbf-622c-76f4-9dd6-2a9730a11515')
def filesDomainId           = string2Uuid('019e9210-52a4-7c31-9b5e-3d8f0a6c1e42')
def dataxrayIdAttrTypeId    = string2Uuid('019e73ae-1aa8-700c-8086-626326822c22')

final String KEEP_IN_SYNC_TAG = 'dataxray-keep-in-sync'
def MAX_TOTAL_FILE_ASSETS = 25_000
def BATCH_SIZE            = 50

// --- Inputs from earlier tasks ------------------------------------------------

def dataxrayUrl       = (execution.getVariable('dataxrayUrl') ?: '').toString().replaceAll('/+$', '')
def dataxrayAuthToken = (execution.getVariable('dataxrayAuthToken') ?: '').toString().trim()
def queryString       = (execution.getVariable('queryString') ?: '').toString()
if (queryString == '(all files)') { queryString = '' }
def conditionName     = (execution.getVariable('conditionName') ?: '').toString()
def queryAssetId      = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
def keepInSync        = execution.getVariable('keepInSync') == true

// --- Stream the query results into the work list ------------------------------

def encodedQuery = URLEncoder.encode(queryString, StandardCharsets.UTF_8.toString())
def searchUrl = "${dataxrayUrl}/api/v1/files?q=${encodedQuery}"

// Data X-Ray can transiently stall an NDJSON stream mid-response — retry a
// fresh attempt before giving up (see search_data_xray.groovy).
def FETCH_ATTEMPTS = 3
def workItems = []
def skippedNoId = 0
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
            if (tuple == null) {
                skippedNoId++
            } else {
                workItems << tuple
            }
        }
    } finally {
        try { input.close() } catch (Exception ignored) { /* best-effort */ }
        conn.disconnect()
    }
    break
    } catch (Exception fetchEx) {
        if (attempt < FETCH_ATTEMPTS) {
            loggerApi.warn("Data X-Ray fetch attempt ${attempt}/${FETCH_ATTEMPTS} failed (${fetchEx.message}) — retrying")
            sleep(5000)
        } else {
            loggerApi.error("Import aborted — could not fetch results from Data X-Ray after ${FETCH_ATTEMPTS} attempts: ${fetchEx.message}")
            def wf = new WorkflowException("Data X-Ray file import failed while fetching results: ${fetchEx.message}", fetchEx)
            wf.setTitleMessage('Data X-Ray import failed')
            wf.setUserMessage("Could not fetch the search results from Data X-Ray to import them (${FETCH_ATTEMPTS} attempts): ${fetchEx.message}")
            throw wf
        }
    }
}
if (skippedNoId > 0) {
    loggerApi.warn("Import: skipped ${skippedNoId} result row(s) without a usable file id")
}

// --- Re-check the instance-wide cap -------------------------------------------

int filesDomainCount = countAssetsInDomain(filesDomainId)
int projectedTotal = filesDomainCount + workItems.size()
if (projectedTotal > MAX_TOTAL_FILE_ASSETS) {
    def msg = "Import refused: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this search matched ${workItems.size()} — the projected ${projectedTotal} would exceed the ${MAX_TOTAL_FILE_ASSETS} instance-wide limit."
    loggerApi.error(msg)
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Data X-Ray import refused')
    wf.setUserMessage(msg)
    throw wf
}

// --- Resolve classifications to Collibra asset UUIDs --------------------------

// Two indexes over the classifications domain: by Data X-Ray ID attribute
// (authoritative — survives renames and name disambiguation) and by asset
// name (fallback for rows that only carry names). Both built once, here.
def classificationAssets = fetchAllAssetsInDomain(classificationsDomainId)
def classAssetIdByName = [:]
classificationAssets.values().each { a -> classAssetIdByName[a.getName()] = a.getId() }
def classAssetIdByDxrId = fetchAssetIdsByDataxrayId(dataxrayIdAttrTypeId, classificationAssets.keySet())

int unresolvedClassifications = 0
workItems.each { tuple ->
    def resolved = [] as Set
    tuple[7].each { ref ->
        // ref is [dxrId, name] as extracted from the row; either part may be ''.
        def hit = (ref[0] ? classAssetIdByDxrId[ref[0]] : null) ?: (ref[1] ? classAssetIdByName[ref[1]] : null)
        if (hit) {
            resolved << hit.toString()
        } else {
            unresolvedClassifications++
        }
    }
    tuple[7] = resolved as List
}
if (unresolvedClassifications > 0) {
    loggerApi.warn("Import: ${unresolvedClassifications} classification reference(s) could not be resolved to Collibra assets (run Sync Data X-Ray Classifications and re-import to link them)")
}

// --- Store the work list -------------------------------------------------------

def json = JsonOutput.toJson(workItems)
def baos = new ByteArrayOutputStream()
def gz = new GZIPOutputStream(baos)
gz.write(json.getBytes(StandardCharsets.UTF_8))
gz.close()
def encoded = Base64.getEncoder().encodeToString(baos.toByteArray())

int batchCount = (int) Math.ceil(workItems.size() / (double) BATCH_SIZE)
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

loggerApi.info("Import collector ready: ${workItems.size()} file(s) in ${batchCount} batch(es) of ${BATCH_SIZE} (work list ${encoded.length()} chars encoded); files domain currently holds ${filesDomainCount}")

// --- Keep-in-sync flag ----------------------------------------------------------

if (keepInSync) {
    try {
        assetApi.addAssetTags(AddAssetTagsRequest.builder()
            .assetId(queryAssetId)
            .tagNames([KEEP_IN_SYNC_TAG])
            .build())
        loggerApi.info("Tagged query asset ${queryAssetId} with ${KEEP_IN_SYNC_TAG} — the nightly file sync will rerun this search")
    } catch (Exception tagEx) {
        loggerApi.warn("Failed to tag query asset ${queryAssetId} with ${KEEP_IN_SYNC_TAG}: ${tagEx.message}")
    }
}

// --- Helpers --------------------------------------------------------------------

// Distill one NDJSON result row into a work-item tuple, or null when the row
// has no usable file id. Field names verified against a live /api/v1/files
// response (see the header) — update here AND in rerun_collector.groovy if
// the Data X-Ray row shape changes.
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

// Map a row's classification entries to [dxrId, name] pairs, keeping only
// entries with positive hit evidence. "Checked but zero hits" is not a match.
// Per entry kind (verified shapes):
//   labels            {id, name}                     – presence = label applied
//   extractedMetadata {id, name, value, type}        – presence = extractor produced metadata
//   annotators        {id, name, uniquePhrases,
//                      annotations: [{phrase, …}]}   – needs uniquePhrases > 0
//                                                      or a non-empty annotations
//                                                      list; checked-with-zero
//                                                      is NOT a match
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
            // Bare string entry: name only, presence == applied.
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
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build())
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
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build())
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
            .typeIds([attrTypeId])
            .limit(1000)
            .cursor(cursor)
            .build())
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
