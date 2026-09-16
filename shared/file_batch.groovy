// file_batch.groovy — upserting one batch of Data X-Ray file assets, and
// retiring the files a query no longer returns.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// Used by the search import, the rerun sync (on-prem: from the work list;
// edge: straight from each fetched page) and the rerun's retire pass.
//
// Per work item (tuple layout in dxr_rows.groovy):
//   – Deterministic asset UUID from the Data X-Ray file id, so the same file
//     always maps to the same Collibra asset: upsert is assetApi.exists().
//   – Missing assets are bulk-created (addAssets) in the Data X-Ray Files
//     domain. The full name carries a short UUID-derived suffix (Collibra
//     names are unique per domain; 10k files WILL repeat "invoice.pdf");
//     the display name is the clean filename.
//   – Existing assets are refreshed: renamed if the filename changed, and
//     reactivated (status Candidate) if a previous sync retired them.
//   – Attributes (Data X-Ray ID, File Path, File Size, Last Modified,
//     Datasource Name, Link) are written with setAssetAttributes (replace-all
//     per type — idempotent).
//   – Relations: query asset —returns→ file asset (the per-query membership
//     the rerun workflow diffs against), and file —groups→ each matched
//     classification. Relations for freshly created assets are added in bulk
//     (no duplicates possible); relations for pre-existing assets are added
//     one by one with individual catches, since Collibra rejects duplicates.
//
// A failing batch is logged, counted as failed, and the caller STILL advances
// — one bad batch must never wedge the loop.

// {{include:dxr_model.groovy}}
// {{include:dxr_rows.groovy}}
// {{include:collibra_lookup.groovy}}

// Upsert one batch of work-item tuples. `label` names the batch in log lines
// (e.g. "Import batch 3/12"). Returns
// [created:, updated:, failed:, relationsAdded:, relationsRemoved:].
def processFileBatch(List batch, UUID queryAssetId, String label) {
    def ids = dxrModelIds()
    int created = 0
    int updated = 0
    int failedInBatch = 0
    int relationsAdded = 0
    int relationsRemoved = 0

    try {
        // --- Split the batch into new vs existing assets ------------------------

        def newItems = []       // [tuple, assetId]
        def existingItems = []  // [tuple, assetId]
        batch.each { tuple ->
            def assetId = deterministicFileAssetId(tuple[0])
            if (assetApi.exists(assetId)) {
                existingItems << [tuple, assetId]
            } else {
                newItems << [tuple, assetId]
            }
        }

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

        // --- Refresh pre-existing assets (rename / reactivate) ------------------

        existingItems.each { pair ->
            def (tuple, assetId) = pair
            try {
                def asset = assetApi.getAsset(assetId)
                def desiredName = uniqueAssetName(fileNameOf(tuple), assetId)
                def needsRename = asset.getName() != desiredName
                def isRetired = asset.getStatus()?.getId() == ids.obsoleteStatusId
                if (needsRename || isRetired) {
                    def change = com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder().id(assetId)
                    if (needsRename) { change.name(desiredName).displayName(fileNameOf(tuple)) }
                    if (isRetired)   { change.statusId(ids.candidateStatusId) }
                    assetApi.changeAsset(change.build())
                    if (isRetired) { loggerApi.info("Reactivated previously retired file asset ${assetId}") }
                }
                updated++
            } catch (Exception refreshEx) {
                failedInBatch++
                loggerApi.warn("Failed to refresh existing file asset ${assetId}: ${refreshEx.message}")
            }
        }

        // --- Attributes (idempotent replace-all per type) ------------------------

        (newItems + existingItems).each { pair ->
            def (tuple, assetId) = pair
            setFileAssetAttr(assetId, ids.dataxrayIdAttrTypeId,   tuple[0])
            setFileAssetAttr(assetId, ids.filePathAttrTypeId,     tuple[2])
            setFileAssetAttr(assetId, ids.fileSizeAttrTypeId,     tuple[4])
            setFileAssetAttr(assetId, ids.lastModifiedAttrTypeId, tuple[5])
            setFileAssetAttr(assetId, ids.datasourceAttrTypeId,   tuple[1])
            setFileAssetAttr(assetId, ids.linkAttrTypeId,         tuple[6])
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
            }

            // Reconcile the file→classification links against the CURRENT hit
            // evidence: add what's missing, REMOVE what's stale — a label deleted
            // or unassigned in Data X-Ray must drop off the file asset on the next
            // sync. A result row carries the file's complete classification state,
            // so the desired set is the same whichever query syncs the file.
            def desired = [] as Set
            tuple[7].each { classId -> desired << string2Uuid(classId.toString()) }
            def existingGroups = relationIdsByFarEnd(ids.groupsRelationTypeId, assetId, null)
            desired.each { cid ->
                if (!existingGroups.containsKey(cid)) {
                    if (addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                            .sourceId(assetId).targetId(cid).typeId(ids.groupsRelationTypeId).build())) {
                        relationsAdded++
                    }
                }
            }
            existingGroups.each { cid, relId ->
                if (!desired.contains(cid)) {
                    try {
                        relationApi.removeRelation(relId)
                        relationsRemoved++
                    } catch (Exception remEx) {
                        loggerApi.warn("Failed to remove stale classification link ${cid} from ${assetId}: ${remEx.message}")
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
            relationsAdded: relationsAdded, relationsRemoved: relationsRemoved]
}

// Add the batch's counters to the running import*Count process variables.
def accumulateBatchCounters(Map r) {
    execution.setVariable('importCreatedCount', ((execution.getVariable('importCreatedCount') ?: 0) as int) + r.created)
    execution.setVariable('importUpdatedCount', ((execution.getVariable('importUpdatedCount') ?: 0) as int) + r.updated)
    execution.setVariable('importFailedCount',  ((execution.getVariable('importFailedCount') ?: 0) as int) + r.failed)
    execution.setVariable('importRelationCount', ((execution.getVariable('importRelationCount') ?: 0) as int) + r.relationsAdded)
    execution.setVariable('importRelationsRemovedCount', ((execution.getVariable('importRelationsRemovedCount') ?: 0) as int) + r.relationsRemoved)
}

// Files the query previously returned but no longer does are unlinked and —
// when NO other query still returns them — RETIRED (status Obsolete). Never
// deleted: comments, attachments, workflow tasks and other activities tied to
// the asset must survive as records. If the file later reappears in any
// query's results, processFileBatch reactivates it (status Candidate).
// `currentIds` / `previousIds` hold file-asset UUID strings.
// Returns [unlinked:, retired:].
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
                }
                // Retire only when no query at all still returns this file.
                def stillReturned = relationApi.findRelations(com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest.builder()
                    .relationTypeId(ids.returnsRelationTypeId)
                    .targetId(fileAssetId)
                    .limit(1)
                    .build())
                if (stillReturned.getResults().isEmpty()) {
                    def current = assetApi.getAsset(fileAssetId)
                    if (current.getStatus()?.getId() != ids.obsoleteStatusId) {
                        assetApi.changeAsset(com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest.builder()
                            .id(fileAssetId)
                            .statusId(ids.obsoleteStatusId)
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
    return [unlinked: unlinked, retired: retired]
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

def addRelationQuietly(request) {
    try {
        relationApi.addRelation(request)
        return true
    } catch (Exception relEx) {
        loggerApi.warn("addRelation failed (may already exist): ${relEx.message}")
        return false
    }
}
