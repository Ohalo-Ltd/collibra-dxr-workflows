// dxr_rows.groovy — the work-item tuple and file identity.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// Both editions read Data X-Ray through its internal search API
// (dxr_search.groovy: tupleFromSearchHit turns one hit into a tuple). Every
// Collibra-side step (file_batch.groovy, the rerun, the results file) works on
// tuples only.
//
// Work-item tuple (positional, to keep the JSON compact):
//   [0] Data X-Ray file id  (String — the index document _id; shown in the
//       "Data X-Ray ID" attribute. NOT the identity: Data X-Ray reassigns it
//       whenever the index is rebuilt)
//   [1] datasource name     (String)
//   [2] full path           (String)
//   [3] file name           (String)
//   [4] size                (String, '' when unknown)
//   [5] last modified       (String, '' when unknown)
//   [6] deep link URL       (String, '' when unknown)
//   [7] matched classifications: [dxrId, name] pairs after extraction, replaced
//       by a list of Collibra asset UUID strings by resolveTupleClassifications()
//   [8] Data X-Ray datasource id (numeric String)
//   [9] Data X-Ray object id     (String — what the connector identifies the
//       file by: the path for SMB/NFS/S3/Azure/SharePoint on-prem, the
//       service's item id for SharePoint Online/OneDrive/Google Drive/Box)
//
// File identity = (datasource id, object id): the key Data X-Ray itself uses
// to recognise a file across scans (ElasticsearchClientIngester), so it
// survives rescans and index rebuilds. It changes when the datasource is
// deleted and recreated (new datasource id), and — on connectors that identify
// files by path — when the file is renamed or moved.

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

// Same file, same asset. The UUID is derived from the file identity (see the
// header), so upsert needs no lookup index and reruns/imports from any query
// converge on the same asset.
def deterministicFileAssetId(Object datasourceId, Object objectId) {
    def key = "dxr-file:${datasourceId}:${objectId}".toString()
    return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
}

def fileAssetIdOf(List tuple) {
    return deterministicFileAssetId(tuple[8], tuple[9])
}

def dataSourceName(f) { (f?.datasource?.name ?: '').toString() }
def filePath(f) { (f?.path ?: f?.filePath ?: '').toString() }


def truncateText(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
}
