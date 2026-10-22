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

// Map of far-end asset id → [id: relation id, createdBy: user id] for an
// asset's relations of one type: pass sourceId to walk targets, or targetId to
// walk sources.
def relationsByFarEnd(UUID relationTypeId, UUID sourceId, UUID targetId) {
    def out = [:]
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
            out[sourceId != null ? rel.getTarget().getId() : rel.getSource().getId()] =
                [id: rel.getId(), createdBy: rel.getCreatedBy()]
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return out
}

// Map of far-end asset id → relation id (see relationsByFarEnd).
def relationIdsByFarEnd(UUID relationTypeId, UUID sourceId, UUID targetId) {
    return relationsByFarEnd(relationTypeId, sourceId, targetId).collectEntries { k, v -> [(k): v.id] }
}

// The users whose relations count as the integration's own: Collibra's
// workflow user (what async tasks run as) and whoever this task runs as.
def integrationIdentityIds() {
    def out = [dxrWorkflowUserId()] as Set
    try {
        def me = userApi.getCurrentUser()
        if (me?.isPresent()) { out << me.get().getId() }
    } catch (Exception meEx) {
        loggerApi.debug("Could not read the current user: ${meEx.message}")
    }
    return out
}

// Every Data X-Ray File asset on the instance, wherever it has been moved —
// cursor-paged by asset type, not domain. Calls `visit` with each asset.
def eachFileAsset(Map ids, Closure visit) {
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest.builder()
            .typeIds([ids.fileTypeId])
            .limit(1000)
            .cursor(cursor)
            .build())
        page.getResults().each { visit(it) }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
}

// The file-asset population the 25,000 cap applies to: every Data X-Ray File
// asset on the instance, including ones moved out of the Files domain.
def countFileAssets(Map ids) {
    int count = 0
    eachFileAsset(ids) { count++ }
    return count
}

// Tag names on an asset ([] when they can't be read).
def assetTagNames(UUID assetId) {
    try {
        return assetApi.getAssetTags(assetId).collect { it.getName() }
    } catch (Exception tagEx) {
        loggerApi.warn("Could not read tags of asset ${assetId}: ${tagEx.message}")
        return []
    }
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

// Remove a tag from an asset, logging (not throwing) on failure.
def removeAssetTagQuietly(UUID assetId, String tagName, String successNote) {
    try {
        assetApi.removeAssetTags(com.collibra.dgc.core.api.dto.instance.asset.RemoveAssetTagsRequest.builder()
            .assetId(assetId)
            .tagNames([tagName])
            .build())
        if (successNote) { loggerApi.info(successNote) }
        return true
    } catch (Exception tagEx) {
        loggerApi.warn("Failed to remove tag ${tagName} from asset ${assetId}: ${tagEx.message}")
        return false
    }
}

// Values of one attribute type across a set of assets: [assetId → value].
def fetchAttributeValuesByType(UUID attrTypeId, Set<UUID> assetIds) {
    def out = [:]
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
            if (aid && val != null && assetIds.contains(aid)) {
                out[aid] = val.toString()
            }
        }
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return out
}

// The Edge edition's classification index, built from Collibra alone (no
// Data X-Ray catalogue calls): every classification asset with its public
// Data X-Ray ID (uuid) and, when the Edge sync has stamped it, the numeric id
// Data X-Ray's search index uses. All keys/values are Strings so the map can
// round-trip through a JSON process variable. Returns
//   [byDxrId: [uuid → assetId], byName: [name → assetId],
//    indexIdByDxrId: [uuid → indexId], nameByDxrId: [uuid → asset name],
//    labelDxrIdByIndexId / annotatorDxrIdByIndexId / extractorDxrIdByIndexId: [indexId → uuid],
//    annotatorIndexIds: [indexId, …] (every annotator known to Collibra)]
def buildEdgeClassificationIndex(Map ids) {
    def assets = fetchAllAssetsInDomain(ids.classificationsDomainId)
    def uuidByAsset  = fetchAttributeValuesByType(ids.dataxrayIdAttrTypeId, assets.keySet())
    def indexByAsset = fetchAttributeValuesByType(ids.dataxrayIndexIdAttrTypeId, assets.keySet())
    def out = [byDxrId: [:], byName: [:], indexIdByDxrId: [:], nameByDxrId: [:],
               labelDxrIdByIndexId: [:], annotatorDxrIdByIndexId: [:], extractorDxrIdByIndexId: [:],
               annotatorIndexIds: []]
    assets.each { assetId, asset ->
        def name = asset.getName()
        out.byName[name] = assetId.toString()
        def uuid = uuidByAsset[assetId]
        if (!uuid) { return }
        out.byDxrId[uuid] = assetId.toString()
        out.nameByDxrId[uuid] = name
        def indexId = indexByAsset[assetId]
        if (!indexId) { return }
        out.indexIdByDxrId[uuid] = indexId
        def typeId = asset.getType()?.getId()
        if (typeId == ids.labelTypeId) {
            out.labelDxrIdByIndexId[indexId] = uuid
        } else if (typeId == ids.extractorTypeId) {
            out.extractorDxrIdByIndexId[indexId] = uuid
        } else if (typeId == ids.annotatorTypeId) {
            out.annotatorDxrIdByIndexId[indexId] = uuid
            out.annotatorIndexIds << indexId
        }
    }
    return out
}
