// sync_files_batch.groovy
//
// KEEP IN SYNC with workflows/search-data-xray/scripts/import_batch.groovy —
// this is its twin for the rerun workflow (same duplication precedent as the
// sync-classifications script pair; Collibra workflows can't share script
// files). It consumes the work list produced by rerun_collector.groovy, which
// uses the same variable names as the search workflow's import collector. An
// aborted rerun leaves importTotal at 0, so the early guard below makes this
// task a no-op.
//
// Second stage of the file sync: an ASYNC script task that Collibra
// re-executes in a loop (exclusive gateway on ${hasMoreWork}) until the work
// list is drained. Each execution is one DB transaction that processes one
// batch of BATCH_SIZE work items — the shape Collibra's bulk-operations
// guidance prescribes for high-volume workflows, so a 10k sync never holds
// one giant transaction.
//
// Per work item (tuple layout documented in import_collector.groovy):
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
// A failing batch is logged, counted into importFailedCount, and the cursor
// STILL advances — one bad batch must never wedge the loop.
//
// Reads:  importWorkList (immutable), importCursor, importTotal, queryAssetId
// Writes: importCursor, hasMoreWork, importCreatedCount, importUpdatedCount,
//         importFailedCount, importRelationCount

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest
import com.collibra.dgc.core.api.dto.instance.asset.ChangeAssetRequest
import com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest
import com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest
import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

// --- Constants (canonical operating-model IDs; see configure workflow) -------

def filesDomainId          = string2Uuid('019e9210-52a4-7c31-9b5e-3d8f0a6c1e42')
def fileTypeId             = string2Uuid('019e9210-6e77-7b02-8c4a-92d15b7f30a9')
def filePathAttrTypeId     = string2Uuid('019e9210-8a3c-70d5-b1e8-604f9c2d7a53')
def fileSizeAttrTypeId     = string2Uuid('019e9210-9bd0-7e46-a927-15c8e03b6f84')
def lastModifiedAttrTypeId = string2Uuid('019e9210-ad15-73f8-bc06-7e94a1d52c37')
def datasourceAttrTypeId   = string2Uuid('019e9210-be62-7a89-90d3-48b6f57e0c21')
def dataxrayIdAttrTypeId   = string2Uuid('019e73ae-1aa8-700c-8086-626326822c22')
def linkAttrTypeId         = string2Uuid('019c9fc5-aa4c-72af-8918-caa54fe61eba')
def returnsRelationTypeId  = string2Uuid('019e9210-f180-79dc-b5a0-6c31e94f82d5')
def groupsRelationTypeId   = string2Uuid('00000000-0000-0000-0000-000000007017')

def OBSOLETE_STATUS_ID  = string2Uuid('00000000-0000-0000-0000-000000005011')
def CANDIDATE_STATUS_ID = string2Uuid('00000000-0000-0000-0000-000000005008')

def BATCH_SIZE = 50

// --- Load state ----------------------------------------------------------------

int total = (execution.getVariable('importTotal') ?: 0) as int
if (total == 0) {
    // Nothing to do (empty result set, or an aborted rerun) — end the loop.
    execution.setVariable('hasMoreWork', false)
    return
}

def queryAssetId = string2Uuid((execution.getVariable('queryAssetId') ?: '').toString())
int cursor = (execution.getVariable('importCursor') ?: 0) as int
int batchCount = (execution.getVariable('importBatchCount') ?: 0) as int

def workItems = decodeWorkList((execution.getVariable('importWorkList') ?: '').toString())
int end = Math.min(cursor + BATCH_SIZE, workItems.size())
def batch = workItems.subList(cursor, end)
int batchNumber = (int) (cursor / BATCH_SIZE) + 1

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
            AddAssetRequest.builder()
                .id(assetId)
                .name(uniqueAssetName(fileNameOf(tuple), assetId))
                .displayName(fileNameOf(tuple))
                .domainId(filesDomainId)
                .typeId(fileTypeId)
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
            def isRetired = asset.getStatus()?.getId() == OBSOLETE_STATUS_ID
            if (needsRename || isRetired) {
                def change = ChangeAssetRequest.builder().id(assetId)
                if (needsRename) { change.name(desiredName).displayName(fileNameOf(tuple)) }
                if (isRetired)   { change.statusId(CANDIDATE_STATUS_ID) }
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
        setAttr(assetId, dataxrayIdAttrTypeId,   tuple[0])
        setAttr(assetId, filePathAttrTypeId,     tuple[2])
        setAttr(assetId, fileSizeAttrTypeId,     tuple[4])
        setAttr(assetId, lastModifiedAttrTypeId, tuple[5])
        setAttr(assetId, datasourceAttrTypeId,   tuple[1])
        setAttr(assetId, linkAttrTypeId,         tuple[6])
    }

    // --- Relations ------------------------------------------------------------

    // Fresh assets can't have duplicate relations — add theirs in bulk.
    def bulkRelations = []
    newItems.each { pair ->
        def (tuple, assetId) = pair
        bulkRelations << AddRelationRequest.builder()
            .sourceId(queryAssetId).targetId(assetId).typeId(returnsRelationTypeId).build()
        tuple[7].each { classId ->
            bulkRelations << AddRelationRequest.builder()
                .sourceId(assetId).targetId(string2Uuid(classId.toString())).typeId(groupsRelationTypeId).build()
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
        def existingReturns = relationIdsByFarEnd(returnsRelationTypeId, null, assetId)
        if (!existingReturns.containsKey(queryAssetId)) {
            if (addRelationQuietly(AddRelationRequest.builder()
                    .sourceId(queryAssetId).targetId(assetId).typeId(returnsRelationTypeId).build())) {
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
        def existingGroups = relationIdsByFarEnd(groupsRelationTypeId, assetId, null)
        desired.each { cid ->
            if (!existingGroups.containsKey(cid)) {
                if (addRelationQuietly(AddRelationRequest.builder()
                        .sourceId(assetId).targetId(cid).typeId(groupsRelationTypeId).build())) {
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
    loggerApi.error("Import batch ${batchNumber}/${batchCount} failed: ${batchEx.message}")
}

// --- Advance the cursor ----------------------------------------------------------

execution.setVariable('importCursor', end)
execution.setVariable('hasMoreWork', end < workItems.size())
execution.setVariable('importCreatedCount', ((execution.getVariable('importCreatedCount') ?: 0) as int) + created)
execution.setVariable('importUpdatedCount', ((execution.getVariable('importUpdatedCount') ?: 0) as int) + updated)
execution.setVariable('importFailedCount',  ((execution.getVariable('importFailedCount') ?: 0) as int) + failedInBatch)
execution.setVariable('importRelationCount', ((execution.getVariable('importRelationCount') ?: 0) as int) + relationsAdded)
execution.setVariable('importRelationsRemovedCount', ((execution.getVariable('importRelationsRemovedCount') ?: 0) as int) + relationsRemoved)

loggerApi.info("Import batch ${batchNumber}/${batchCount}: +${created} created, +${updated} updated, +${failedInBatch} failed, +${relationsAdded}/-${relationsRemoved} relation(s); ${end}/${total} done")

// --- Helpers ----------------------------------------------------------------------

def decodeWorkList(String encoded) {
    if (encoded.isEmpty()) { return [] }
    def bytes = Base64.getDecoder().decode(encoded)
    def gz = new GZIPInputStream(new ByteArrayInputStream(bytes))
    def json = new String(gz.readAllBytes(), StandardCharsets.UTF_8)
    return new JsonSlurper().parseText(json)
}

// Same file, same asset — forever. The UUID is derived from the Data X-Ray
// file id, so upsert needs no lookup index and reruns/imports from any query
// converge on the same asset.
def deterministicFileAssetId(fileId) {
    return UUID.nameUUIDFromBytes(("dxr-file:" + fileId).getBytes(StandardCharsets.UTF_8))
}

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

def setAttr(UUID assetId, UUID typeId, value) {
    def values = (value == null || value.toString().trim().isEmpty()) ? [] : [value.toString()]
    if (values.isEmpty()) { return }
    try {
        assetApi.setAssetAttributes(SetAssetAttributesRequest.builder()
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

// Map of far-end asset id → relation id for an asset's relations of one type:
// pass sourceId to walk targets, or targetId to walk sources.
def relationIdsByFarEnd(UUID relationTypeId, UUID sourceId, UUID targetId) {
    def ids = [:]
    def cursor = ''
    while (true) {
        def builder = FindRelationsRequest.builder()
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
