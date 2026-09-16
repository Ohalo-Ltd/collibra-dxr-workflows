// dxr_rows.groovy — Data X-Ray result-row parsing and the work-item tuple.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// This is the ONE place that knows the shape of a Data X-Ray file row. Every
// transport (direct /api/v1/files NDJSON on-prem, paged internal search via
// Collibra Edge) funnels rows through here, so a Data X-Ray field rename is a
// one-file fix.
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
//   [7] matched classifications: [dxrId, name] pairs after extraction, replaced
//       by a list of Collibra asset UUID strings by resolveTupleClassifications()
//
// Hit evidence vs "checked with zero": a result row may list a classification
// that was checked but produced no hits — that is NOT a match and must not
// become a relation. extractClassifications() therefore requires positive
// evidence per entry kind. Row shape verified against a live Data X-Ray
// /api/v1/files NDJSON response (Data X-Ray demo instance, 2026-08):
//   { datasource: {id, name, connector}, fileName, fileId, path, size,
//     mimeType, createdAt, lastModifiedAt, contentSha256, scanDepth,
//     labels: [{id, name}],
//     extractedMetadata: [{id, name, value, type}],
//     annotators: [{id, name, domain, uniquePhrases,
//                   annotations: [{phrase, locations: [{start, end}]}]}],
//     dlpLabels, externalMetadata, entitlements, owner, ... }

// Distill one /api/v1/files row into a work-item tuple, or null when the row
// has no usable file id.
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

// Replace every tuple's [dxrId, name] classification refs with the matching
// Collibra asset UUID strings (by Data X-Ray ID first, then by name). Returns
// the number of refs that could not be resolved. `classIndex` comes from
// buildClassificationIndex() (collibra_lookup.groovy).
def resolveTupleClassifications(List workItems, Map classIndex) {
    int unresolved = 0
    workItems.each { tuple ->
        def resolved = [] as Set
        tuple[7].each { ref ->
            // ref is [dxrId, name] as extracted from the row; either part may be ''.
            def hit = (ref[0] ? classIndex.byDxrId[ref[0]] : null) ?: (ref[1] ? classIndex.byName[ref[1]] : null)
            if (hit) {
                resolved << hit.toString()
            } else {
                unresolved++
            }
        }
        tuple[7] = resolved as List
    }
    return unresolved
}

// Same file, same asset — forever. The UUID is derived from the Data X-Ray
// file id, so upsert needs no lookup index and reruns/imports from any query
// converge on the same asset.
def deterministicFileAssetId(fileId) {
    return UUID.nameUUIDFromBytes(("dxr-file:" + fileId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
}

// The classification catalogue from a GET /api/v1/classifications body:
// { data: [{id, name, type, subtype, description, link, searchLink, …}] }.
def parseClassificationsBody(String body) {
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(body)
    } catch (Exception parseEx) {
        throw new RuntimeException("Data X-Ray API returned non-JSON body: ${parseEx.message}")
    }
    def data = parsed?.data
    if (!(data instanceof List)) {
        throw new RuntimeException("Data X-Ray API response missing 'data' array; got keys: ${parsed?.keySet()}")
    }
    return data
}

def dataSourceName(f) { (f?.datasource?.name ?: '').toString() }
def filePath(f) { (f?.path ?: f?.filePath ?: '').toString() }

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

def truncateText(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
}
