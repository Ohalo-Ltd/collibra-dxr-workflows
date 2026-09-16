// collibra_lookup.groovy — read-side helpers over the Collibra Java API.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// Uses fully-qualified DTO names so a script's own import block stays the only
// import block (Groovy wants imports at the top of the file).

// Exact population of a domain, counted by cursor-paging. findAssets' total
// field is unreliable on cursor pages, so counting the pages is the safe way;
// at the 25k cap this is ~25 fast calls.
def countAssetsInDomain(UUID domainId) {
    int count = 0
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest.builder()
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

// Every asset in a domain, keyed by UUID.
def fetchAllAssetsInDomain(UUID domainId) {
    def byId = [:]
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest.builder()
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

// Assets in a domain carrying a given tag.
def fetchTaggedAssetsInDomain(UUID domainId, String tagName) {
    def results = []
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest.builder()
            .domainId(domainId)
            .tagNames([tagName])
            .limit(1000)
            .cursor(cursor)
            .build())
        results.addAll(page.getResults())
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return results
}

// Map Data X-Ray ID attribute value → Collibra asset UUID, restricted to the
// given assets. findAttributes has no domain filter, so we query globally by
// type and then drop any hits that belong to assets outside the set.
def fetchAssetIdsByDataxrayId(UUID attrTypeId, Set<UUID> assetsInDomain) {
    def assetIdByDataxrayId = [:]
    def cursor = ''
    while (true) {
        def page = attributeApi.findAttributes(com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest.builder()
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

// Two indexes over the classifications domain for resolving a file row's
// classification refs to Collibra assets: by Data X-Ray ID attribute
// (authoritative — survives renames and name disambiguation) and by asset
// name (fallback for rows that only carry names). Built once per run.
// Returns [byDxrId: [dxrId → UUID], byName: [name → UUID]].
def buildClassificationIndex(UUID classificationsDomainId, UUID dataxrayIdAttrTypeId) {
    def classificationAssets = fetchAllAssetsInDomain(classificationsDomainId)
    def byName = [:]
    classificationAssets.values().each { a -> byName[a.getName()] = a.getId() }
    def byDxrId = fetchAssetIdsByDataxrayId(dataxrayIdAttrTypeId, classificationAssets.keySet())
    return [byDxrId: byDxrId, byName: byName]
}

// Map of far-end asset id → relation id for an asset's relations of one type:
// pass sourceId to walk targets, or targetId to walk sources.
def relationIdsByFarEnd(UUID relationTypeId, UUID sourceId, UUID targetId) {
    def ids = [:]
    def cursor = ''
    while (true) {
        def builder = com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest.builder()
            .relationTypeId(relationTypeId)
            .limit(1000)
            .cursor(cursor)
        if (sourceId != null) { builder.sourceId(sourceId) }
        if (targetId != null) { builder.targetId(targetId) }
        def page = relationApi.findRelations(builder.build())
        page.getResults().each { rel ->
            ids[sourceId != null ? rel.getTarget().getId() : rel.getSource().getId()] = rel.getId()
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return ids
}

// The single (first) value of an attribute type on an asset, '' when absent.
def readSingleAttribute(UUID assetId, UUID typeId) {
    try {
        def page = attributeApi.findAttributes(com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest.builder()
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

// Tag an asset, logging (not throwing) on failure. addAssetTags is idempotent.
def addAssetTagQuietly(UUID assetId, String tagName, String successNote) {
    try {
        assetApi.addAssetTags(com.collibra.dgc.core.api.dto.instance.asset.AddAssetTagsRequest.builder()
            .assetId(assetId)
            .tagNames([tagName])
            .build())
        if (successNote) { loggerApi.info(successNote) }
        return true
    } catch (Exception tagEx) {
        loggerApi.warn("Failed to tag asset ${assetId} with ${tagName}: ${tagEx.message}")
        return false
    }
}
