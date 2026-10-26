// file_batch.groovy — upserting one batch of DataXray file assets, and
// retiring the files a query no longer returns.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// Used by the search import, the rerun sync (on-prem: from the work list;
// edge: straight from each fetched page) and the rerun's retire pass.
//
// Per work item (tuple layout in dxr_rows.groovy):
//   – Identity: a file's asset is the one with the UUID derived from its
//     DataXray file id; failing that, the one whose stored object key
//     (DataXray Datasource ID + Object ID) matches the row's, so a file
//     DataXray gives a new id (its index dropped and rescanned) keeps its
//     asset. See resolveFileAssetIds below.
//   – Missing assets are bulk-created (addAssets) in the DataXray Files
//     domain. The full name carries a short UUID-derived suffix (Collibra
//     names are unique per domain; 10k files WILL repeat "invoice.pdf");
//     the display name is the clean filename. Both are set only here: a
//     rename in Collibra is kept, and DataXray's current filename goes to the
//     DataXray File Name attribute (v2.7).
//   – Existing assets are reactivated (status Candidate) if a previous sync
//     parked them as 'Not in any search' (or Obsolete, before v2.4). A status
//     a person set (Accepted, Under Review, …) is never changed.
//   – Attributes (DataXray ID, DataXray File Name, File Path, File Size, Last
//     Modified, Datasource Name, Link) are written with setAssetAttributes (replace-all
//     per type — idempotent); size and date in readable form (see below).
//     DataXray Extracted Values (optional, extractedValuesOn) is rewritten as a
//     table of the file's extractor values, or cleared when it has none.
//   – Relations: query asset —returns→ file asset (the per-query membership
//     the rerun workflow diffs against), and file —groups→ each matched
//     classification. Relations for freshly created assets are added in bulk
//     (no duplicates possible); relations for pre-existing assets are added
//     one by one with individual catches, since Collibra rejects duplicates.
//   – Links that stop applying are not deleted but swapped to a "formerly"
//     relation type (query —formerly returns→ file, file —formerly groups→
//     classification), and swapped back if they apply again. Only links the
//     integration created (createdBy = the workflow user) are ever touched: a
//     file→classification link a person made is left exactly as it is.
//   – opts.createNew == false (query tagged dataxray-no-auto-import): files
//     with no asset yet are counted as skippedNew and not created.
//
// A failing batch is logged, counted as failed, and the caller STILL advances
// — one bad batch must never wedge the loop.

// {{include:dxr_model.groovy}}
// {{include:dxr_rows.groovy}}
// {{include:collibra_lookup.groovy}}

// Upsert one batch of work-item tuples. `label` names the batch in log lines
// (e.g. "Import batch 3/12"). opts.createNew (default true): false refreshes
// existing assets only. Returns
// [created:, updated:, failed:, relationsAdded:, relationsRemoved:, skippedNew:,
//  matchedByObjectKey:, seenAssetIds: (the asset UUID string each tuple maps to)].
def processFileBatch(List batch, UUID queryAssetId, String label, Map opts = [:]) {
    def ids = dxrModelIds()
    boolean createNew = opts.createNew != false
    int created = 0
    int updated = 0
    int failedInBatch = 0
    int relationsAdded = 0
    int relationsRemoved = 0
    int skippedNew = 0
    def resolved = batch.collect { deterministicFileAssetId(it[0]) }
    int matchedByObjectKey = 0

    try {
        // --- Split the batch into new vs existing assets ------------------------

        def newItems = []       // [tuple, assetId]
        def existingItems = []  // [tuple, assetId]
        def found = resolveFileAssetIds(batch, ids)
        batch.eachWithIndex { tuple, i ->
            def assetId = found[i]
            if (assetId != null) {
                if (assetId != resolved[i]) { matchedByObjectKey++ }
                resolved[i] = assetId
                existingItems << [tuple, assetId]
            } else if (createNew) {
                newItems << [tuple, resolved[i]]
            } else {
                skippedNew++
            }
        }
        if (matchedByObjectKey > 0) {
            loggerApi.info("${label}: ${matchedByObjectKey} file(s) DataXray gave a new id matched their existing asset by object key")
        }
        if (skippedNew > 0) {
            loggerApi.info("${label}: ${skippedNew} new matching file(s) not imported (automatic import is off for this search)")
        }
        def owned = integrationIdentityIds()

        // --- Bulk-create the missing assets -------------------------------------

        if (!newItems.isEmpty()) {
            def addRequests = newItems.collect { pair ->
                def (tuple, assetId) = pair
                com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest.builder()
                    .id(assetId)
                    .name(uniqueAssetName(fileNameOf(tuple), assetId))
                    .displayName(fileNameOf(tuple))
                    .domainId(ids.filesDomainId)
                    .typeId(ids.fileTypeId)
                    .build()
            }
            assetApi.addAssets(addRequests)
            created = newItems.size()
        }

        // --- Refresh pre-existing assets (reactivate) ----------------------------

        // The asset's name is set from DataXray's file name once, at creation,
        // and is the user's after that: a rename in Collibra sticks. DataXray's
        // current name is kept in the DataXray File Name attribute instead.
        existingItems.each { pair ->
            def (tuple, assetId) = pair
            try {
                def asset = assetApi.getAsset(assetId)
                // Only a status the integration set is flipped back; a status a
                // person chose stays as it is.
                if (isParkedFileStatus(asset.getStatus()?.getId(), ids)) {
                    assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                        .id(assetId).statusId(ids.candidateStatusId).build())
                    loggerApi.info("Reactivated file asset ${assetId} (was '${asset.getStatus()?.getName()}')")
                }
                updated++
            } catch (Exception refreshEx) {
                failedInBatch++
                loggerApi.warn("Failed to refresh existing file asset ${assetId}: ${refreshEx.message}")
            }
        }

        // --- Attributes (idempotent replace-all per type) ------------------------

        boolean valuesOn = extractedValuesOn()

        (newItems + existingItems).each { pair ->
            def (tuple, assetId) = pair
            setFileAssetAttr(assetId, ids.dataxrayIdAttrTypeId,   tuple[0])
            setFileAssetAttr(assetId, ids.fileNameAttrTypeId,     fileNameOf(tuple))
            setFileAssetAttr(assetId, ids.filePathAttrTypeId,     tuple[2])
            setFileAssetAttr(assetId, ids.fileSizeAttrTypeId,     readableFileSize(tuple[4]))
            setFileAssetAttr(assetId, ids.lastModifiedAttrTypeId, readableTimestamp(tuple[5]))
            setFileAssetAttr(assetId, ids.datasourceAttrTypeId,   tuple[1])
            setFileAssetAttr(assetId, ids.linkAttrTypeId,         tuple[6])
            if (fileObjectKey(tuple) != null) {
                setFileAssetAttr(assetId, ids.datasourceIdAttrTypeId, tuple[8])
                setFileAssetAttr(assetId, ids.objectIdAttrTypeId,     tuple[9])
            }
            if (valuesOn) {
                replaceFileAssetAttr(assetId, ids.extractedValuesAttrTypeId,
                                     renderExtractedValuesHtml(tuple.size() > 10 ? tuple[10] : []))
            }
        }

        // --- Relations ------------------------------------------------------------

        // Fresh assets can't have duplicate relations — add theirs in bulk.
        def bulkRelations = []
        newItems.each { pair ->
            def (tuple, assetId) = pair
            bulkRelations << com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                .sourceId(queryAssetId).targetId(assetId).typeId(ids.returnsRelationTypeId).build()
            tuple[7].each { classId ->
                bulkRelations << com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                    .sourceId(assetId).targetId(string2Uuid(classId.toString())).typeId(ids.groupsRelationTypeId).build()
            }
        }
        if (!bulkRelations.isEmpty()) {
            try {
                relationApi.addRelations(bulkRelations)
                relationsAdded += bulkRelations.size()
            } catch (Exception bulkEx) {
                // Fall back to one-by-one so a single bad target doesn't sink the rest.
                loggerApi.warn("Bulk relation add failed (${bulkEx.message}); retrying individually")
                bulkRelations.each { req -> if (addRelationQuietly(req)) { relationsAdded++ } }
            }
        }

        // Pre-existing assets may already carry these relations (this query on a
        // retry, other queries for the classification links) — Collibra rejects
        // duplicates, so add each individually and skip the ones already present.
        existingItems.each { pair ->
            def (tuple, assetId) = pair
            def existingReturns = relationIdsByFarEnd(ids.returnsRelationTypeId, null, assetId)
            if (!existingReturns.containsKey(queryAssetId)) {
                if (addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                        .sourceId(queryAssetId).targetId(assetId).typeId(ids.returnsRelationTypeId).build())) {
                    relationsAdded++
                }
                // The query returns the file again: drop its "formerly returns".
                def formerly = relationsByFarEnd(ids.formerlyReturnsRelationTypeId, null, assetId)[queryAssetId]
                if (formerly != null) { removeRelationQuietly(formerly.id) }
            }

            // Reconcile the file→classification links against the CURRENT hit
            // evidence. A result row carries the file's complete classification
            // state, so the desired set is the same whichever query syncs the
            // file. Missing links are added (or swapped back from "formerly");
            // a link the integration made that no longer applies is swapped to
            // "formerly groups" — a label deleted or unassigned in DataXray
            // stays visible as history instead of vanishing. Links a person
            // created are never removed, and a person's link to a classification
            // the file matches means nothing is added.
            def desired = [] as Set
            tuple[7].each { classId -> desired << string2Uuid(classId.toString()) }
            def existingGroups = relationsByFarEnd(ids.groupsRelationTypeId, assetId, null)
            Map formerlyGroups = null
            def formerlyGroupsOf = {
                if (formerlyGroups == null) { formerlyGroups = relationsByFarEnd(ids.formerlyGroupsRelationTypeId, assetId, null) }
                return formerlyGroups
            }
            desired.each { cid ->
                if (!existingGroups.containsKey(cid)) {
                    if (addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                            .sourceId(assetId).targetId(cid).typeId(ids.groupsRelationTypeId).build())) {
                        relationsAdded++
                    }
                    def formerly = formerlyGroupsOf()[cid]
                    if (formerly != null && owned.contains(formerly.createdBy)) { removeRelationQuietly(formerly.id) }
                }
            }
            existingGroups.each { cid, rel ->
                if (desired.contains(cid)) { return }
                if (!owned.contains(rel.createdBy)) {
                    loggerApi.debug("Kept classification link ${cid} on ${assetId}: created by a person (${rel.createdBy}), not the integration")
                    return
                }
                if (removeRelationQuietly(rel.id)) {
                    relationsRemoved++
                    if (!formerlyGroupsOf().containsKey(cid)) {
                        addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                            .sourceId(assetId).targetId(cid).typeId(ids.formerlyGroupsRelationTypeId).build())
                    }
                }
            }
        }
    } catch (Exception batchEx) {
        // Count the whole batch as failed but keep the loop moving — one bad batch
        // must never wedge the import.
        failedInBatch = batch.size()
        created = 0
        updated = 0
        loggerApi.error("${label} failed: ${batchEx.message}")
    }

    return [created: created, updated: updated, failed: failedInBatch,
            relationsAdded: relationsAdded, relationsRemoved: relationsRemoved, skippedNew: skippedNew,
            matchedByObjectKey: matchedByObjectKey, seenAssetIds: resolved.collect { it.toString() }]
}

// --- File identity -------------------------------------------------------------
//
// DataXray's file id changes when a datasource's scanned data is dropped from
// the index and scanned again; the datasource id and the source system's
// object id (the path on file shares and buckets, the item id on SharePoint
// Online, OneDrive, …) do not. Every asset written from a row that carries
// both stores them (DataXray Datasource ID, DataXray Object ID), and a row
// whose file id has no asset is looked up by them before a new asset is made.
// The file id is tried first: an unchanged id is the same file, and the
// object-key index is then never loaded.

// The row's object key, or null when DataXray did not supply both parts.
def fileObjectKey(List tuple) {
    def ds  = tuple.size() > 9 ? (tuple[8] ?: '').toString() : ''
    def obj = tuple.size() > 9 ? (tuple[9] ?: '').toString() : ''
    return (ds && obj) ? "${ds}:${obj}".toString() : null
}

// The existing asset UUID for each tuple (null = no asset yet), in order.
def resolveFileAssetIds(List batch, Map ids) {
    Map keyIndex = null
    return batch.collect { tuple ->
        def byFileId = deterministicFileAssetId(tuple[0])
        if (assetApi.exists(byFileId)) { return byFileId }
        def key = fileObjectKey(tuple)
        if (key == null) { return null }
        if (keyIndex == null) { keyIndex = loadFileObjectKeyIndex(ids) }
        def hit = keyIndex[key]
        if (hit && assetApi.exists(string2Uuid(hit))) {
            loggerApi.info("File ${tuple[0]} has a new DataXray id; matched asset ${hit} by object key ${key}")
            return string2Uuid(hit)
        }
        return null
    }
}

// object key → asset UUID string over every file asset that has both parts.
// Built from Collibra once per workflow run (two attribute scans) and kept in
// the process variable dxrObjectKeyIndex (gzip + Base64) for later batches
// and pages. Assets created during the run are not in it: they were created
// under their current file id, which is tried first.
def loadFileObjectKeyIndex(Map ids) {
    def cached = (execution.getVariable('dxrObjectKeyIndex') ?: '').toString()
    if (cached) { return unpackJson(cached) as Map }
    def index = [:]
    try {
        def dsByAsset  = attributeValuesOfType(ids.datasourceIdAttrTypeId)
        def objByAsset = attributeValuesOfType(ids.objectIdAttrTypeId)
        int clashes = 0
        objByAsset.each { assetId, obj ->
            def ds = dsByAsset[assetId]
            if (!ds) { return }
            def key = "${ds}:${obj}".toString()
            if (index.containsKey(key)) { clashes++ } else { index[key] = assetId }
        }
        if (clashes > 0) { loggerApi.warn("${clashes} file asset(s) share an object key with another; the first found is used") }
    } catch (Exception indexEx) {
        loggerApi.warn("Could not build the object-key index (${indexEx.message}); files are matched by DataXray file id only")
    }
    loggerApi.info("Object-key index: ${index.size()} file asset(s)")
    execution.setVariable('dxrObjectKeyIndex', packJson(index))
    return index
}

// asset UUID string → value, for every attribute of one type.
def attributeValuesOfType(UUID typeId) {
    def out = [:]
    def cursor = ''
    while (true) {
        def page = attributeApi.findAttributes(com.collibra.dgc.core.api.dto.instance.attribute.FindAttributesRequest.builder()
            .typeIds([typeId])
            .limit(1000)
            .cursor(cursor)
            .build())
        page.getResults().each { attr ->
            def aid = attr.getAsset()?.getId()
            def val = attr.getValue()
            if (aid && val != null && !val.toString().isEmpty()) { out[aid.toString()] = val.toString() }
        }
        cursor = page.getNextCursor()
        if (!cursor) { break }
    }
    return out
}

def packJson(Object value) {
    def baos = new ByteArrayOutputStream()
    def gz = new java.util.zip.GZIPOutputStream(baos)
    gz.write(groovy.json.JsonOutput.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    gz.close()
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

def unpackJson(String packed) {
    def gz = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(packed)))
    return new groovy.json.JsonSlurper().parseText(new String(gz.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
}

// Add the batch's counters to the running import*Count process variables.
def accumulateBatchCounters(Map r) {
    execution.setVariable('importCreatedCount', ((execution.getVariable('importCreatedCount') ?: 0) as int) + r.created)
    execution.setVariable('importUpdatedCount', ((execution.getVariable('importUpdatedCount') ?: 0) as int) + r.updated)
    execution.setVariable('importFailedCount',  ((execution.getVariable('importFailedCount') ?: 0) as int) + r.failed)
    execution.setVariable('importRelationCount', ((execution.getVariable('importRelationCount') ?: 0) as int) + r.relationsAdded)
    execution.setVariable('importRelationsRemovedCount', ((execution.getVariable('importRelationsRemovedCount') ?: 0) as int) + r.relationsRemoved)
    execution.setVariable('importSkippedNewCount', ((execution.getVariable('importSkippedNewCount') ?: 0) as int) + (r.skippedNew ?: 0))
}

// Files the query previously returned but no longer does: the query's
// "returns" link is swapped to "formerly returns", and — when NO other query
// still returns the file — its status becomes 'Not in any search'. Never
// deleted: comments, attachments, workflow tasks and other activities tied to
// the asset must survive as records. A status a person set is left alone (the
// links are still swapped). If the file later reappears in any query's
// results, processFileBatch sets it back to Candidate and swaps the link back.
// `currentIds` / `previousIds` hold file-asset UUID strings.
// Returns [unlinked:, retired:] — retired = files now in no search.
def retireOrphanFiles(Set currentIds, Collection previousIds, UUID queryAssetId) {
    def ids = dxrModelIds()
    def orphanIds = previousIds.findAll { !currentIds.contains(it.toString()) }
    int unlinked = 0
    int retired = 0

    if (!orphanIds.isEmpty()) {
        // Map file-asset id → relation id for this query's "returns" relations, so
        // each orphan's relation can be removed by id.
        def relationIdByTarget = [:]
        relationIdsByFarEnd(ids.returnsRelationTypeId, queryAssetId, null).each { targetId, relId ->
            relationIdByTarget[targetId.toString()] = relId
        }

        orphanIds.each { orphan ->
            def fileAssetId = string2Uuid(orphan.toString())
            try {
                def relId = relationIdByTarget[orphan.toString()]
                if (relId != null) {
                    relationApi.removeRelation(relId)
                    unlinked++
                    addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                        .sourceId(queryAssetId).targetId(fileAssetId).typeId(ids.formerlyReturnsRelationTypeId).build())
                }
                // Park only when no query at all still returns this file.
                def stillReturned = relationApi.findRelations(com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest.builder()
                    .relationTypeId(ids.returnsRelationTypeId)
                    .targetId(fileAssetId)
                    .limit(1)
                    .build())
                if (stillReturned.getResults().isEmpty()) {
                    def current = assetApi.getAsset(fileAssetId)
                    parkFileAsset(current, ids, 'no query returns it any more')
                    retired++
                }
            } catch (Exception orphanEx) {
                loggerApi.error("Failed to unlink/park orphan file asset ${fileAssetId}: ${orphanEx.message}")
            }
        }
    }
    return [unlinked: unlinked, retired: retired]
}

// Set a file asset no search returns to 'Not in any search' — unless a person
// set its status (anything but Candidate / Obsolete), which is kept. Returns
// true when the status was changed.
def parkFileAsset(asset, Map ids, String reason) {
    def statusId = asset.getStatus()?.getId()
    // 'Not in DataXray' already says more (no search returns it, and DataXray
    // no longer has it): keep it.
    if (statusId == ids.notInAnySearchStatusId || statusId == ids.notInDataXrayStatusId) { return false }
    if (!isIntegrationOwnedFileStatus(statusId, ids)) {
        loggerApi.info("File asset '${asset.getName()}' [${asset.getId()}] is in no search (${reason}); kept its status '${asset.getStatus()?.getName()}', set by a person")
        return false
    }
    assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
        .id(asset.getId())
        .statusId(ids.notInAnySearchStatusId)
        .build())
    loggerApi.info("File asset '${asset.getName()}' [${asset.getId()}] set to 'Not in any search' — ${reason}")
    return true
}

// --- Helpers ----------------------------------------------------------------------

// Clean filename for the asset's display name: the row's fileName field,
// falling back to the path's basename.
def fileNameOf(tuple) {
    def name = (tuple[3] ?: '').toString()
    if (name.isEmpty()) {
        def p = (tuple[2] ?: '').toString()
        def cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'))
        name = cut >= 0 ? p.substring(cut + 1) : p
    }
    return name.isEmpty() ? '(unnamed file)' : name.take(240)
}

// Collibra asset names are unique per domain and filenames repeat constantly,
// so every full name carries a short suffix derived from the deterministic
// asset UUID. The clean filename lives in displayName (the File asset type has
// display names enabled).
def uniqueAssetName(String fileName, UUID assetId) {
    def frag = assetId.toString().replaceAll('-', '').take(8)
    return "${fileName.take(200)} [${frag}]".toString()
}

// File Size for people: "27.2 KB (27,885 bytes)" (1 KB = 1,024 bytes; the exact
// count stays in brackets). A value that isn't a whole number is kept as is.
def readableFileSize(value) {
    def raw = (value == null ? '' : value.toString().trim())
    if (!raw.isLong()) { return raw }
    long bytes = raw.toLong()
    def exact = String.format(Locale.ENGLISH, '%,d', bytes)
    if (bytes < 1024) { return "${exact} ${bytes == 1L ? 'byte' : 'bytes'}".toString() }
    def units = ['KB', 'MB', 'GB', 'TB', 'PB']
    double v = bytes / 1024d
    int u = 0
    while (v >= 1024d && u < units.size() - 1) { v = v / 1024d; u++ }
    return "${String.format(Locale.ENGLISH, '%.1f', v)} ${units[u]} (${exact} bytes)".toString()
}

// Last Modified for people: "10 Apr 2025, 17:22 UTC". DataXray sends ISO-8601
// instants (with or without fractional seconds or an offset); a value without
// a zone is read as UTC, digits alone as epoch seconds or milliseconds.
// Anything else is kept as is.
def readableTimestamp(value) {
    def raw = (value == null ? '' : value.toString().trim())
    if (raw.isEmpty()) { return raw }
    java.time.Instant instant = null
    try {
        if (raw.isLong()) {
            long n = raw.toLong()
            instant = n > 100_000_000_000L ? java.time.Instant.ofEpochMilli(n) : java.time.Instant.ofEpochSecond(n)
        } else {
            try {
                instant = java.time.OffsetDateTime.parse(raw).toInstant()
            } catch (java.time.format.DateTimeParseException noOffset) {
                instant = java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC)
            }
        }
    } catch (Exception parseEx) {
        return raw
    }
    return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
        .withZone(java.time.ZoneOffset.UTC).format(instant)
}

def setFileAssetAttr(UUID assetId, UUID typeId, value) {
    def values = (value == null || value.toString().trim().isEmpty()) ? [] : [value.toString()]
    if (values.isEmpty()) { return }
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .values(values as List<Object>)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("setAssetAttributes(${typeId}) on ${assetId} failed: ${attrEx.message}")
    }
}

// Like setFileAssetAttr, but an empty value clears the attribute (no stale values).
def replaceFileAssetAttr(UUID assetId, UUID typeId, String value) {
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .values((value ? [value] : []) as List<Object>)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("setAssetAttributes(${typeId}) on ${assetId} failed: ${attrEx.message}")
    }
}

// The DataXray Extracted Values table: one row per extractor, its values one
// per line. '' when the file has none.
//
// Styled inline: Collibra's rich-text view has no table CSS of its own. Its
// sanitizer keeps style attributes (normalised: 1px → 1.0px, #hex → rgb())
// but drops word-break and overflow-wrap; the fixed layout with set column
// widths plus word-wrap is what makes long JSON values wrap (checked on
// ohalo.collibra.com, 26 Oct 2026).
def renderExtractedValuesHtml(List pairs) {
    if (!pairs) { return '' }
    def byExtractor = [:]
    pairs.each { p -> byExtractor.get(p[0].toString(), []) << p[1].toString() }
    def border = 'border:1px solid #c8c8c8;'
    def cell = border + 'padding:4px 8px;text-align:left;vertical-align:top;word-wrap:break-word;'
    def writer = new StringWriter()
    def html = new groovy.xml.MarkupBuilder(new groovy.util.IndentPrinter(writer, '', false))
    html.doubleQuotes = true
    html.table(style: border + 'border-collapse:collapse;width:100%;table-layout:fixed;') {
        colgroup { col(style: 'width:30%;'); col(style: 'width:70%;') }
        thead {
            tr {
                th(style: cell + 'background-color:#f2f2f2;font-weight:bold;', 'Extractor')
                th(style: cell + 'background-color:#f2f2f2;font-weight:bold;', 'Value')
            }
        }
        tbody {
            byExtractor.each { name, values ->
                tr {
                    td(style: cell, name)
                    td(style: cell) {
                        values.eachWithIndex { v, i ->
                            if (i > 0) { br() }
                            mkp.yield(v)
                        }
                    }
                }
            }
        }
    }
    return writer.toString()
}

def removeRelationQuietly(UUID relationId) {
    try {
        relationApi.removeRelation(relationId)
        return true
    } catch (Exception relEx) {
        loggerApi.warn("removeRelation ${relationId} failed: ${relEx.message}")
        return false
    }
}

def addRelationQuietly(request) {
    try {
        relationApi.addRelation(request)
        return true
    } catch (Exception relEx) {
        loggerApi.warn("addRelation failed (may already exist): ${relEx.message}")
        return false
    }
}
