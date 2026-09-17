// search_common.groovy — helpers for the Search Data X-Ray workflow (both variants).
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).

// {{include:dxr_rows.groovy}}
// {{include:collibra_lookup.groovy}}

// Normalize an asset-picker value to a list of trimmed, non-empty ID strings.
// Pickers may hand back a List, a single String, or a comma-separated String.
def toIdList(value) {
    if (value == null) return []
    def raw = (value instanceof Collection) ? value.toList() : value.toString().split(',').toList()
    return raw.collect { it?.toString()?.trim() }.findAll { it }
}

// Look up each picked asset. Returns [[id: UUID, name: String], …] for the
// ones that resolve; unresolvable ids are logged and skipped.
def resolvePickedAssets(List ids) {
    def resolved = []
    ids.each { id ->
        def targetId = string2Uuid(id)
        try {
            resolved << [id: targetId, name: assetApi.getAsset(targetId).getName()]
        } catch (Exception resolveEx) {
            loggerApi.warn("Skipping classification ${id}: ${resolveEx.message}")
        }
    }
    return resolved
}

// Relate the source asset to every target in one bulk call.
def relateAssets(UUID sourceId, List<UUID> targetIds, UUID relationTypeId) {
    if (targetIds.isEmpty()) { return }
    relationApi.addRelations(targetIds.collect { targetId ->
        com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
            .sourceId(sourceId)
            .targetId(targetId)
            .typeId(relationTypeId)
            .build()
    })
}

// Look up each asset by ID, relate it to the query asset, and return the names.
def resolveAndRelate(List ids, UUID sourceId, UUID relationTypeId) {
    def resolved = resolvePickedAssets(ids)
    relateAssets(sourceId, resolved.collect { it.id }, relationTypeId)
    return resolved.collect { it.name }
}

// Create the search-query asset; throws a user-facing WorkflowException on failure.
def createQueryAsset(String conditionName, Map ids) {
    try {
        def asset = assetApi.addAsset(com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest.builder()
            .name(conditionName)
            .displayName(conditionName)
            .domainId(ids.queryDomainId)
            .typeId(ids.queryAssetTypeId)
            .build())
        loggerApi.info("Created search-query asset '${conditionName}' [${asset.getId()}]")
        return asset.getId()
    } catch (Exception createEx) {
        loggerApi.error("Failed to create search-query asset '${conditionName}': ${createEx.message}")
        def wf = new com.collibra.dgc.workflow.api.exception.WorkflowException("Could not create the search-query asset: ${createEx.message}", createEx)
        wf.setTitleMessage('Search Data X-Ray failed')
        wf.setUserMessage("Could not create the search-query asset '${conditionName}': ${createEx.message}")
        throw wf
    }
}

// Add an attribute value, logging (not throwing) on failure; blank values are skipped.
def addAttributeQuietly(UUID assetId, UUID typeId, String value) {
    if (value == null || value.trim().isEmpty()) return
    try {
        attributeApi.addAttribute(com.collibra.dgc.core.api.dto.instance.attribute.AddAttributeRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .value(value)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("Failed to add attribute ${typeId} on ${assetId}: ${attrEx.message}")
    }
}

// Write the query asset's Description ("<user text>\n\nThe query is: …"), the
// Data X-Ray Query record and, when present, the Annotated Text Filter.
def writeQueryAttributes(UUID queryId, Map ids, String description, String queryString, String filter) {
    def descParts = []
    if (description) descParts << description
    descParts << "The query is: ${displayQuery(queryString)}".toString()
    addAttributeQuietly(queryId, ids.descriptionAttrTypeId, descParts.join('\n\n'))
    // Structured criteria for the rerun workflow. The classification criteria are
    // already recoverable from the query asset's "groups" relations (rebuilt from
    // CURRENT names at rerun time, so Data X-Ray renames don't break saved
    // queries) and the phrase's annotators from its "searches text in" relations;
    // the free-text phrase has no relation, so it gets its own attribute. The
    // composed query string is stored too — as a human-readable record of what
    // ran, not as rerun input.
    addAttributeQuietly(queryId, ids.queryAttrTypeId, displayQuery(queryString))
    if (filter) {
        addAttributeQuietly(queryId, ids.filterAttrTypeId, filter)
    }
}

// Filename derived from the search name; keeps only filesystem-safe characters.
def safeAttachmentName(String conditionName, String suffix) {
    def safeName = conditionName.replaceAll('[^A-Za-z0-9._-]+', '_').replaceAll('^_+|_+$', '')
    if (safeName.isEmpty()) safeName = 'search'
    return "${safeName}${suffix}".toString()
}

// Attach bytes to an asset. Best-effort: returns false (after a warning) on failure.
def attachFileToAsset(UUID assetId, String fileName, byte[] bytes) {
    try {
        attachmentApi.addAttachment(com.collibra.dgc.core.api.dto.instance.attachment.AddAttachmentRequest.builder()
            .baseResourceId(assetId)
            .baseResourceDiscriminator('Asset')
            .fileName(fileName)
            .fileStream(new ByteArrayInputStream(bytes))
            .build())
        loggerApi.info("Attached ${fileName} to asset ${assetId}")
        return true
    } catch (Exception attachEx) {
        loggerApi.warn("Failed to attach ${fileName} to asset ${assetId}: ${attachEx.message}")
        return false
    }
}

// The "Files" attribute on the query asset: an HTML preview table of the first
// matches (or a plain sentence for an empty result).
def renderPreviewHtml(List shown, String totalDisplay, String moreNote) {
    if (shown.isEmpty()) {
        return 'Query returned 0 results'
    }
    def writer = new StringWriter()
    def html = new groovy.xml.MarkupBuilder(writer)
    html.div {
        p("Preview — first ${shown.size()} of ${totalDisplay} matching file(s).${moreNote}".toString())
        table {
            thead { tr { th('Datasource'); th('Path') } }
            tbody {
                shown.each { f ->
                    tr { td(dataSourceName(f)); td(filePath(f)) }
                }
            }
        }
    }
    return writer.toString()
}

// Import feasibility against the INSTANCE-WIDE cap. The cap applies to the
// TOTAL population of the Data X-Ray Files domain, not to this result set
// alone; for a fresh query the projection is current population + result
// count — conservative when results overlap files another query already
// imported (those would be updates, not new assets).
// Returns [importAllowed:, importWarn:, importBlocked:, importBlockedMessage:, projectedTotal:].
def computeImportFeasibility(int total, int filesDomainCount, String totalDisplay) {
    int projectedTotal = filesDomainCount + total
    boolean importAllowed = total > 0 && projectedTotal <= dxrMaxTotalFileAssets()
    boolean importWarn    = importAllowed && total > dxrWarnImportFiles()
    boolean importBlocked = total > 0 && !importAllowed
    def importBlockedMessage = importBlocked
        ? "Importing is disabled for this search: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this search matched ${totalDisplay} file(s) — the projected ${projectedTotal} would exceed the ${dxrMaxTotalFileAssets()} instance-wide limit. Narrow the query, or retire imported searches you no longer need.".toString()
        : ''
    return [importAllowed: importAllowed, importWarn: importWarn, importBlocked: importBlocked,
            importBlockedMessage: importBlockedMessage, projectedTotal: projectedTotal]
}

// Escape one CSV field per RFC 4180: quote it when it contains a comma, quote,
// CR or LF, doubling any embedded quotes.
def csvEscape(value) {
    def s = (value ?: '').toString()
    if (s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
        return '"' + s.replace('"', '""') + '"'
    }
    return s
}

// Look up each picked classification asset and its "Data X-Ray ID" attribute
// (the UUID Data X-Ray's public catalogue uses). Returns
// [[id: UUID, name: String, dxrId: String], …]; unresolvable ids are skipped
// with a warning, an asset without the attribute gets dxrId ''.
def resolvePickedClassifications(List ids, UUID dataxrayIdAttrTypeId, UUID indexIdAttrTypeId = null) {
    return resolvePickedAssets(ids).collect { a ->
        [id: a.id, name: a.name,
         dxrId: readSingleAttribute(a.id, dataxrayIdAttrTypeId),
         indexId: indexIdAttrTypeId ? readSingleAttribute(a.id, indexIdAttrTypeId) : '']
    }
}
