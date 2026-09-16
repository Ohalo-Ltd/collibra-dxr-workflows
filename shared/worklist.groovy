// worklist.groovy — the gzip+Base64 work-list process variable.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// ON-PREM VARIANT: the collector script streams every Data X-Ray row into one
// immutable process variable (importWorkList); the async batch task consumes
// it 50 rows at a time, advancing only an integer cursor. The blob is written
// once and never rewritten. (The edge variant processes each fetched page
// directly and has no work list.)

def encodeWorkList(List workItems) {
    def json = groovy.json.JsonOutput.toJson(workItems)
    def baos = new ByteArrayOutputStream()
    def gz = new java.util.zip.GZIPOutputStream(baos)
    gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    gz.close()
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

def decodeWorkList(String encoded) {
    if (encoded == null || encoded.isEmpty()) { return [] }
    def bytes = Base64.getDecoder().decode(encoded)
    def gz = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes))
    def json = new String(gz.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    return new groovy.json.JsonSlurper().parseText(json)
}

// Zero every counter the batch/retire stages advance and publish an (empty)
// work list — the shape both collectors emit before the loop starts.
def publishWorkList(List workItems, int unresolvedClassifications) {
    int batchCount = (int) Math.ceil(workItems.size() / (double) dxrBatchSize())
    execution.setVariable('importWorkList', encodeWorkList(workItems))
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
    return batchCount
}
