// dxr_model.groovy — canonical operating-model IDs and pack-wide constants.
//
// SHARED FILE: pulled into scripts with `// {{include:dxr_model.groovy}}` by the
// harness at build time. Only `def` functions live here (no top-level
// statements), so include order never matters and every script sees one copy.
//
// The IDs are fixed across all instances: configure-data-xray-workflows creates
// the matching community/domains/types/relation types with exactly these UUIDs,
// and searchDataXrayForm.form's asset pickers bake in the same values.

// Every canonical UUID the Data X-Ray workflows reference, as java.util.UUID.
def dxrModelIds() {
    return [
        // Domains and asset types
        queryDomainId          : string2Uuid('019dcf96-233a-72e1-bf25-8398b8c9146e'),  // Data X-Ray Queries
        queryAssetTypeId       : string2Uuid('019dcf97-3bac-72c3-8b59-b6ddbe8a8396'),  // Unstructured Data Query
        classificationsDomainId: string2Uuid('019c9fbf-622c-76f4-9dd6-2a9730a11515'),  // Data X-Ray Classifications
        filesDomainId          : string2Uuid('019e9210-52a4-7c31-9b5e-3d8f0a6c1e42'),  // Data X-Ray Files
        fileTypeId             : string2Uuid('019e9210-6e77-7b02-8c4a-92d15b7f30a9'),  // Data X-Ray File
        classificationTypeId   : string2Uuid('01965d43-235d-796b-be49-078f91d7472a'),
        annotatorTypeId        : string2Uuid('01922a69-e7a0-7ac7-a581-c9ba9286ccf1'),
        extractorTypeId        : string2Uuid('019c9fbd-a91b-7242-9451-79ab632163a3'),
        labelTypeId            : string2Uuid('019c9fbe-25c3-71b7-90ac-057dd582fa1e'),
        // Relation types
        groupsRelationTypeId   : string2Uuid('00000000-0000-0000-0000-000000007017'),  // groups (query→classification, file→classification)
        returnsRelationTypeId  : string2Uuid('019e9210-f180-79dc-b5a0-6c31e94f82d5'),  // returns / returned by (query→file)
        textFilterRelTypeId    : string2Uuid('019e9210-f2a1-7d3e-8c4b-5a6f7e8d9c01'),  // searches text in (query→Annotator)
        formerlyReturnsRelationTypeId: string2Uuid('019e9212-1b22-7d4f-8c3a-6e5f7a8b9c01'), // formerly returns (query→file): the query stopped returning the file
        formerlyGroupsRelationTypeId : string2Uuid('019e9212-2c33-7e50-9d4b-7f6a8b9c0d12'), // formerly groups (file→classification): Data X-Ray no longer finds it on the file
        // Attribute types
        descriptionAttrTypeId  : string2Uuid('00000000-0000-0000-0000-000000003114'),  // Collibra system Description
        filesAttrTypeId        : string2Uuid('019e2736-8bd0-727a-b4ab-6899517a3e73'),  // Files (HTML preview table)
        queryAttrTypeId        : string2Uuid('019e9210-cf9b-751a-85f2-d30c7a48b9e6'),  // Data X-Ray Query
        filterAttrTypeId       : string2Uuid('019e9210-e04d-7c6b-a481-5f29d8036c7a'),  // Annotated Text Filter
        filePathAttrTypeId     : string2Uuid('019e9210-8a3c-70d5-b1e8-604f9c2d7a53'),
        fileSizeAttrTypeId     : string2Uuid('019e9210-9bd0-7e46-a927-15c8e03b6f84'),
        lastModifiedAttrTypeId : string2Uuid('019e9210-ad15-73f8-bc06-7e94a1d52c37'),
        datasourceAttrTypeId   : string2Uuid('019e9210-be62-7a89-90d3-48b6f57e0c21'),
        dataxrayIdAttrTypeId   : string2Uuid('019e73ae-1aa8-700c-8086-626326822c22'),  // Data X-Ray ID (public UUID)
        dataxrayIndexIdAttrTypeId: string2Uuid('019e9211-4a7c-7b1e-9d3f-2c8e5f6a0b41'), // Data X-Ray Index ID (numeric id in DXR's search index; stamped by the Edge-edition sync)
        linkAttrTypeId         : string2Uuid('019c9fc5-aa4c-72af-8918-caa54fe61eba'),
        searchLinkAttrTypeId   : string2Uuid('019c9fc5-8ff5-77a7-962d-4b6b05c69254'),
        subtypeAttrTypeId      : string2Uuid('019c9fc5-ecc8-759b-9c0b-78547fa315ad'),
        // Standard statuses (same UUIDs on every instance)
        obsoleteStatusId       : string2Uuid('00000000-0000-0000-0000-000000005011'),
        candidateStatusId      : string2Uuid('00000000-0000-0000-0000-000000005008'),
        // Custom status created by configure: a file asset no saved search returns any more
        notInAnySearchStatusId : string2Uuid('019e9212-0a11-7c3e-9b2f-5d4e6f7a8b90'),
    ]
}

// Import capacity is INSTANCE-WIDE: the Data X-Ray Files domain must never hold
// more than this many assets in total — not per import. Imports projected to
// exceed it are refused.
def dxrMaxTotalFileAssets() { return 25_000 }

// Cloud + Edge edition: the most rows a search's results file (ZIP'd CSV) holds.
// Collecting it takes one External API call per 100 rows, so a larger search
// gets no file (the results task says so); Data X-Ray's own paging window may
// lower it further.
def dxrEdgeResultsFileMaxRows() { return 10_000 }

// Above this many files an import is allowed but the results form warns that
// it will take a while.
def dxrWarnImportFiles() { return 10_000 }

// Work items processed per async batch task execution (one DB transaction).
def dxrBatchSize() { return 50 }

// Edge edition: results fetched per External API task. Collibra caps a response
// at 100 KB; a row carries one hit-count field per matching annotator, so rows
// grow with the size of the catalogue. 25 rows stayed under 40 KB on a demo
// tenant with ~60 annotators. Configurable per workflow (dataxrayPageSize).
def dxrEdgeDefaultPageSize() { return 25 }
def dxrEdgeMaxPageSize() { return 100 }

// The release this build came from, stamped in by the harness at build time
// ({{pack:version}} / {{pack:variant}}): the tag, e.g. 'v2.3.0', or
// 'dev-<commit>' for a dev deploy, and the bundle ('onprem' / 'cloud-edge').
def dxrPackVersion() { return '{{pack:version}}' }
def dxrPackEdition() { return '{{pack:variant}}' }
def dxrVersionLabel() { return "Data X-Ray workflows ${dxrPackVersion()} (${dxrPackEdition()})".toString() }

// Log which release is running and publish it for the task forms' footer.
def announceDxrVersion(String workflowName) {
    execution.setVariable('dxrVersionLabel', dxrVersionLabel())
    loggerApi.info("${workflowName}: ${dxrVersionLabel()}")
}

// Tag on a query asset that opts it into the nightly file sync.
def dxrKeepInSyncTag() { return 'dataxray-keep-in-sync' }

// Tag on a query asset that stops reruns (manual or nightly) creating assets
// for NEW matching files: they refresh the file assets already in Collibra only.
def dxrNoAutoImportTag() { return 'dataxray-no-auto-import' }

// Collibra's built-in workflow user. Asynchronous script tasks run as this
// user, so every asset and relation the integration writes records it as
// createdBy. Relations created by anyone else were made by a person (or
// another process) and the integration never removes or changes them.
def dxrWorkflowUserId() { return string2Uuid('00000000-0000-0000-0000-000000900003') }

// File-asset statuses the integration manages. Any other status on a file asset
// was set by a person (Accepted, Under Review, …) and is never changed by a sync.
def isIntegrationOwnedFileStatus(UUID statusId, Map ids) {
    return statusId == null || statusId == ids.candidateStatusId ||
        statusId == ids.notInAnySearchStatusId || statusId == ids.obsoleteStatusId
}

// Statuses a sync flips back to Candidate when a search matches the file again:
// 'Not in any search', and Obsolete (what releases before v2.4 used).
def isParkedFileStatus(UUID statusId, Map ids) {
    return statusId == ids.notInAnySearchStatusId || statusId == ids.obsoleteStatusId
}

// Tag stamped on every classification asset written by the classification sync.
def dxrClassificationSyncTag() { return 'dataxray-classification-sync' }

// Why a rerun can't run yet, or '' when it can: the v2.4 lifecycle needs the
// 'Not in any search' status and the "formerly" relation types that
// Configure Data X-Ray Workflows creates.
def rerunModelGap(Map ids) {
    def missing = []
    if (!statusApi.exists(ids.notInAnySearchStatusId)) { missing << "the status 'Not in any search'" }
    if (!relationTypeApi.exists(ids.formerlyReturnsRelationTypeId)) { missing << "the relation type 'formerly returns'" }
    if (!relationTypeApi.exists(ids.formerlyGroupsRelationTypeId)) { missing << "the relation type 'formerly groups'" }
    if (missing.isEmpty()) { return '' }
    return "This instance is missing ${missing.join(', ')}. Run Configure Data X-Ray Workflows once (as an admin) after upgrading the Data X-Ray workflows, then rerun.".toString()
}
