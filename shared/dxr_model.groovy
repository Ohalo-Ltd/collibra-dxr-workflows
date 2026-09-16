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
        // Attribute types
        descriptionAttrTypeId  : string2Uuid('00000000-0000-0000-0000-000000003114'),  // Collibra system Description
        filesAttrTypeId        : string2Uuid('019e2736-8bd0-727a-b4ab-6899517a3e73'),  // Files (HTML preview table)
        queryAttrTypeId        : string2Uuid('019e9210-cf9b-751a-85f2-d30c7a48b9e6'),  // Data X-Ray Query
        filterAttrTypeId       : string2Uuid('019e9210-e04d-7c6b-a481-5f29d8036c7a'),  // Annotated Text Filter
        filePathAttrTypeId     : string2Uuid('019e9210-8a3c-70d5-b1e8-604f9c2d7a53'),
        fileSizeAttrTypeId     : string2Uuid('019e9210-9bd0-7e46-a927-15c8e03b6f84'),
        lastModifiedAttrTypeId : string2Uuid('019e9210-ad15-73f8-bc06-7e94a1d52c37'),
        datasourceAttrTypeId   : string2Uuid('019e9210-be62-7a89-90d3-48b6f57e0c21'),
        dataxrayIdAttrTypeId   : string2Uuid('019e73ae-1aa8-700c-8086-626326822c22'),  // Data X-Ray ID
        linkAttrTypeId         : string2Uuid('019c9fc5-aa4c-72af-8918-caa54fe61eba'),
        searchLinkAttrTypeId   : string2Uuid('019c9fc5-8ff5-77a7-962d-4b6b05c69254'),
        subtypeAttrTypeId      : string2Uuid('019c9fc5-ecc8-759b-9c0b-78547fa315ad'),
        // Standard statuses (same UUIDs on every instance)
        obsoleteStatusId       : string2Uuid('00000000-0000-0000-0000-000000005011'),
        candidateStatusId      : string2Uuid('00000000-0000-0000-0000-000000005008'),
    ]
}

// Import capacity is INSTANCE-WIDE: the Data X-Ray Files domain must never hold
// more than this many assets in total — not per import. Imports projected to
// exceed it are refused.
def dxrMaxTotalFileAssets() { return 25_000 }

// Above this many files an import is allowed but the results form warns that
// it will take a while.
def dxrWarnImportFiles() { return 10_000 }

// Work items processed per async batch task execution (one DB transaction).
def dxrBatchSize() { return 50 }

// Tag on a query asset that opts it into the nightly file sync.
def dxrKeepInSyncTag() { return 'dataxray-keep-in-sync' }

// Tag stamped on every classification asset written by the classification sync.
def dxrClassificationSyncTag() { return 'dataxray-classification-sync' }
