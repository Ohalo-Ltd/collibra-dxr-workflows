// configure_data_xray.groovy
//
// One-time, idempotent operating-model bootstrap for the Data X-Ray workflows,
// run by an admin from the + Create menu.
//
// What it does:
//   1. Ensures the community, domains, asset types and attribute types the
//      Data X-Ray sync & search workflows depend on all exist — creating any
//      that are missing with FIXED canonical UUIDs. Those UUIDs match the
//      defaults shipped in the sync/search BPMNs and the hardcoded picker IDs
//      in searchDataXrayForm.form, so a fresh instance lines up with no edits.
//   2. Ensures the custom attribute types are surfaced on the asset pages by
//      creating a per-type assignment — but only where one isn't already in
//      effect, so existing (hand-tuned) assignments are never disturbed.
//   3. Writes those canonical IDs into the configuration variables of the three
//      Data X-Ray workflows (sync, nightly sync, search). It NEVER touches the
//      Base URL or Bearer token — the admin sets those by hand.
//
// Idempotent: anything that already exists is left untouched. Safe to re-run.
//
// Operating-model elements are created/looked up via the meta APIs
// (assetTypeApi, attributeTypeApi, assignmentApi, statusApi) and the instance
// APIs (communityApi, domainApi). Configuration variables on the other
// workflows are written via workflowDefinitionApi. Every step is wrapped so a
// single failure is reported but never aborts the rest of the run.
//
// Process variables produced (for the results form):
//   configureCreatedCount  (int)    – elements created this run
//   configureSkippedCount  (int)    – elements already present, left untouched
//   configurePatchedCount  (int)    – workflows whose settings were written
//   configureFailedCount   (int)    – steps that errored
//   configureDetail        (String) – HTML breakdown of the above

import com.collibra.dgc.core.api.dto.instance.community.AddCommunityRequest
import com.collibra.dgc.core.api.dto.instance.domain.AddDomainRequest
import com.collibra.dgc.core.api.dto.meta.assettype.AddAssetTypeRequest
import com.collibra.dgc.core.api.dto.meta.attributetype.AddAttributeTypeRequest
import com.collibra.dgc.core.api.dto.meta.attributetype.AttributeKind
import com.collibra.dgc.core.api.dto.assignment.AddAssignmentRequest
import com.collibra.dgc.core.api.dto.assignment.CharacteristicTypeAssignmentReference
import com.collibra.dgc.core.api.dto.workflow.ChangeWorkflowDefinitionRequest
import com.collibra.dgc.core.api.model.meta.type.AssetTypeSymbolType
import com.collibra.dgc.core.api.model.meta.type.attribute.StringType

// --- Canonical identifiers (fixed across instances) -------------------------

def COMMUNITY_ID   = '019c9fbf-0ab2-7258-b118-e68d3e814fe1'
def COMMUNITY_NAME = 'Collibra to Data xRay Integration'

// System "Data Asset Domain" domain type — present on every instance.
def DOMAIN_TYPE_ID = '00000000-0000-0000-0000-000000030001'

// System attribute type — present on every instance.
def DESCRIPTION_ATTR_ID = '00000000-0000-0000-0000-000000003114'

// Custom attribute type IDs.
def LINK_ATTR_ID        = '019c9fc5-aa4c-72af-8918-caa54fe61eba'
def SEARCH_LINK_ATTR_ID = '019c9fc5-8ff5-77a7-962d-4b6b05c69254'
def SUBTYPE_ATTR_ID     = '019c9fc5-ecc8-759b-9c0b-78547fa315ad'
def DXID_ATTR_ID        = '019e73ae-1aa8-700c-8086-626326822c22'
def FILES_ATTR_ID       = '019e2736-8bd0-727a-b4ab-6899517a3e73'

// System parent asset types — present on every instance.
def DATA_CONCEPT_TYPE_ID = '00000000-0000-0000-0000-000000031113'
def DATA_ASSET_TYPE_ID   = '00000000-0000-0000-0000-000000031002'

def domainDefs = [
    [id: '019c9fbf-622c-76f4-9dd6-2a9730a11515', name: 'Data xRay Classifications'],
    [id: '019dcf96-233a-72e1-bf25-8398b8c9146e', name: 'Data xRay Custom Queries'],
]

def assetTypeDefs = [
    [id: '01965d43-235d-796b-be49-078f91d7472a', name: 'Classification',
     parent: DATA_CONCEPT_TYPE_ID, symbol: 'ICON_CODE', color: '#0B31E8',
     icon: 'uf-additional-icon-communication_speech-bubble-lines', acronym: null],
    [id: '01922a69-e7a0-7ac7-a581-c9ba9286ccf1', name: 'Annotator',
     parent: DATA_ASSET_TYPE_ID, symbol: 'ACRONYM_CODE', color: '#25c7c7',
     icon: null, acronym: 'ANN'],
    [id: '019c9fbd-a91b-7242-9451-79ab632163a3', name: 'Extractor',
     parent: DATA_ASSET_TYPE_ID, symbol: 'ACRONYM_CODE', color: '#1db1b1',
     icon: null, acronym: 'EXT'],
    [id: '019c9fbe-25c3-71b7-90ac-057dd582fa1e', name: 'Label',
     parent: DATA_ASSET_TYPE_ID, symbol: 'ICON_CODE', color: '#3e9bd9',
     icon: 'uf-additional-icon-symbol_direction', acronym: null],
    [id: '019dcf97-3bac-72c3-8b59-b6ddbe8a8396', name: 'Unstructured Data Query',
     parent: DATA_ASSET_TYPE_ID, symbol: 'ICON_CODE', color: '#1fb07f',
     icon: 'uf-additional-icon-tool_wrench', acronym: null],
]

def attrTypeDefs = [
    [id: LINK_ATTR_ID,        name: 'Link',          stringType: 'PLAIN_TEXT'],
    [id: SEARCH_LINK_ATTR_ID, name: 'Search Link',   stringType: 'PLAIN_TEXT'],
    [id: SUBTYPE_ATTR_ID,     name: 'Sub Type',      stringType: 'PLAIN_TEXT'],
    [id: DXID_ATTR_ID,        name: 'Data X-Ray ID', stringType: 'PLAIN_TEXT'],
    [id: FILES_ATTR_ID,       name: 'Files',         stringType: 'RICH_TEXT'],
]

// Which custom attribute types each asset type should surface on its page.
def assignmentDefs = [
    [assetTypeId: '01965d43-235d-796b-be49-078f91d7472a', name: 'Classification',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID]],
    [assetTypeId: '01922a69-e7a0-7ac7-a581-c9ba9286ccf1', name: 'Annotator',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID]],
    [assetTypeId: '019c9fbd-a91b-7242-9451-79ab632163a3', name: 'Extractor',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID]],
    [assetTypeId: '019c9fbe-25c3-71b7-90ac-057dd582fa1e', name: 'Label',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID]],
    [assetTypeId: '019dcf97-3bac-72c3-8b59-b6ddbe8a8396', name: 'Unstructured Data Query',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, FILES_ATTR_ID, DXID_ATTR_ID]],
]

// Standard out-of-the-box statuses, Candidate first (it becomes the default).
def STANDARD_STATUS_IDS = [
    '00000000-0000-0000-0000-000000005008', // Candidate (default)
    '00000000-0000-0000-0000-000000005019', // In Progress
    '00000000-0000-0000-0000-000000005020', // Under Review
    '00000000-0000-0000-0000-000000005021', // Reviewed
    '00000000-0000-0000-0000-000000005022', // Invalid
    '00000000-0000-0000-0000-000000005023', // Approval Pending
    '00000000-0000-0000-0000-000000005024', // Access Granted
    '00000000-0000-0000-0000-000000005009', // Accepted
    '00000000-0000-0000-0000-000000005010', // Rejected
    '00000000-0000-0000-0000-000000005011', // Obsolete
    '00000000-0000-0000-0000-000000005055', // Implemented
]

// Configuration variables to write onto the sync workflows (model IDs only —
// never the Base URL or Bearer token).
def MODEL_SYNC_VARS = [
    classificationsDomainId  : '019c9fbf-622c-76f4-9dd6-2a9730a11515',
    classificationAssetTypeId: '01965d43-235d-796b-be49-078f91d7472a',
    annotatorAssetTypeId     : '01922a69-e7a0-7ac7-a581-c9ba9286ccf1',
    extractorAssetTypeId     : '019c9fbd-a91b-7242-9451-79ab632163a3',
    labelAssetTypeId         : '019c9fbe-25c3-71b7-90ac-057dd582fa1e',
    linkAttrTypeId           : LINK_ATTR_ID,
    searchLinkAttrTypeId     : SEARCH_LINK_ATTR_ID,
    subtypeAttrTypeId        : SUBTYPE_ATTR_ID,
    dataxrayIdAttrTypeId     : DXID_ATTR_ID,
]

def SEARCH_VARS = [
    queryDomainId        : '019dcf96-233a-72e1-bf25-8398b8c9146e',
    queryAssetTypeId     : '019dcf97-3bac-72c3-8b59-b6ddbe8a8396',
    groupsRelationTypeId : '00000000-0000-0000-0000-000000007017',
    descriptionAttrTypeId: DESCRIPTION_ATTR_ID,
    filesAttrTypeId      : FILES_ATTR_ID,
]

// --- Result accumulators ----------------------------------------------------

def created = []
def skipped = []
def patched = []
def failed  = []

// --- Phase 1: community -----------------------------------------------------

try {
    def cid = string2Uuid(COMMUNITY_ID)
    if (communityApi.exists(cid)) {
        skipped << "Community '${COMMUNITY_NAME}'"
    } else {
        communityApi.addCommunity(AddCommunityRequest.builder()
            .id(cid).name(COMMUNITY_NAME).build())
        created << "Community '${COMMUNITY_NAME}'"
        loggerApi.info("Created community ${COMMUNITY_NAME} [${COMMUNITY_ID}]")
    }
} catch (Exception e) {
    failed << "Community '${COMMUNITY_NAME}': ${e.message}"
    loggerApi.error("ensure community failed: ${e.message}")
}

// --- Phase 2: domains -------------------------------------------------------

domainDefs.each { d ->
    try {
        def did = string2Uuid(d.id)
        if (domainApi.exists(did)) {
            skipped << "Domain '${d.name}'"
        } else {
            domainApi.addDomain(AddDomainRequest.builder()
                .id(did)
                .name(d.name)
                .communityId(string2Uuid(COMMUNITY_ID))
                .typeId(string2Uuid(DOMAIN_TYPE_ID))
                .build())
            created << "Domain '${d.name}'"
            loggerApi.info("Created domain ${d.name} [${d.id}]")
        }
    } catch (Exception e) {
        failed << "Domain '${d.name}': ${e.message}"
        loggerApi.error("ensure domain ${d.name} failed: ${e.message}")
    }
}

// --- Phase 3: asset types ---------------------------------------------------

assetTypeDefs.each { t ->
    try {
        def tid = string2Uuid(t.id)
        if (assetTypeApi.exists(tid)) {
            skipped << "Asset type '${t.name}'"
        } else {
            def b = AddAssetTypeRequest.builder()
                .id(tid)
                .name(t.name)
                .parentId(string2Uuid(t.parent))
                .symbolType(AssetTypeSymbolType.valueOf(t.symbol))
                .color(t.color)
                .displayNameEnabled(false)
                .ratingEnabled(false)
            if (t.icon)    { b.iconCode(t.icon) }
            if (t.acronym) { b.acronymCode(t.acronym) }
            assetTypeApi.addAssetType(b.build())
            created << "Asset type '${t.name}'"
            loggerApi.info("Created asset type ${t.name} [${t.id}]")
        }
    } catch (Exception e) {
        failed << "Asset type '${t.name}': ${e.message}"
        loggerApi.error("ensure asset type ${t.name} failed: ${e.message}")
    }
}

// --- Phase 4: attribute types -----------------------------------------------

attrTypeDefs.each { a ->
    try {
        def aid = string2Uuid(a.id)
        if (attributeTypeApi.exists(aid)) {
            skipped << "Attribute type '${a.name}'"
        } else {
            attributeTypeApi.addAttributeType(AddAttributeTypeRequest.builder()
                .id(aid)
                .name(a.name)
                .kind(AttributeKind.STRING)
                .stringType(StringType.valueOf(a.stringType))
                .build())
            created << "Attribute type '${a.name}'"
            loggerApi.info("Created attribute type ${a.name} [${a.id}]")
        }
    } catch (Exception e) {
        failed << "Attribute type '${a.name}': ${e.message}"
        loggerApi.error("ensure attribute type ${a.name} failed: ${e.message}")
    }
}

// --- Phase 5: assignments (best-effort, never disturb existing) -------------

// Resolve the standard statuses once; only keep those that exist here.
def statusUuids = STANDARD_STATUS_IDS
    .findAll { statusApi.exists(string2Uuid(it)) }
    .collect { string2Uuid(it) }

assignmentDefs.each { spec ->
    try {
        def tid = string2Uuid(spec.assetTypeId)
        def applicable = assignmentApi.getAssignmentsForAssetType(tid)

        // If this asset type already owns an assignment, leave it untouched —
        // augmenting a hand-tuned assignment is risky and unnecessary (the
        // workflows write attributes via the API regardless of assignment).
        def ownsOwn = applicable.any { it.getAssetType().getId() == tid }
        if (ownsOwn) {
            skipped << "Assignment for '${spec.name}' (already has its own)"
            return
        }

        // Only inherits: if the inherited assignment already surfaces every
        // custom attribute, there is nothing to do.
        def assignedAttrIds = [] as Set
        applicable.each { asg ->
            asg.getAssignedAttributeTypeReferences().each { ref ->
                assignedAttrIds << ref.getAssignedResourceReference().getId()
            }
        }
        def customUuids = spec.customAttrs.collect { string2Uuid(it) }
        if (customUuids.every { assignedAttrIds.contains(it) }) {
            skipped << "Assignment for '${spec.name}' (custom attributes already inherited)"
            return
        }

        if (statusUuids.isEmpty()) {
            failed << "Assignment for '${spec.name}': no standard statuses available"
            return
        }

        // Create an own assignment: Description (mandatory) + custom attributes.
        def refs = []
        refs << CharacteristicTypeAssignmentReference.builder()
            .id(string2Uuid(DESCRIPTION_ATTR_ID))
            .resourceDiscriminator('AttributeType')
            .min(1)
            .build()
        spec.customAttrs.each { attrId ->
            refs << CharacteristicTypeAssignmentReference.builder()
                .id(string2Uuid(attrId))
                .resourceDiscriminator('AttributeType')
                .min(0)
                .build()
        }

        assignmentApi.addAssignment(AddAssignmentRequest.builder()
            .assetTypeId(tid)
            .statusIds(statusUuids)
            .characteristicTypes(refs)
            .build())
        created << "Assignment for '${spec.name}'"
        loggerApi.info("Created assignment for asset type ${spec.name} [${spec.assetTypeId}]")
    } catch (Exception e) {
        failed << "Assignment for '${spec.name}': ${e.message}"
        loggerApi.error("ensure assignment ${spec.name} failed: ${e.message}")
    }
}

// --- Phase 6: write configuration variables onto the other workflows --------

def patchWorkflow = { String processId, Map vars ->
    try {
        def wd = workflowDefinitionApi.getWorkflowDefinitionByProcessId(processId)
        if (wd == null) {
            failed << "Configure '${processId}': workflow not deployed yet"
            return
        }
        def b = ChangeWorkflowDefinitionRequest.builder().id(wd.getId())
        vars.each { k, v -> b.addConfigurationVariable(k, v) }
        workflowDefinitionApi.changeWorkflowDefinition(b.build())
        patched << "${processId} (${vars.size()} setting(s))"
        loggerApi.info("Configured workflow ${processId} with ${vars.size()} model variable(s)")
    } catch (Exception e) {
        failed << "Configure '${processId}': ${e.message} (is it deployed?)"
        loggerApi.error("patch workflow ${processId} failed: ${e.message}")
    }
}

patchWorkflow('syncDataXrayClassifications', MODEL_SYNC_VARS)
patchWorkflow('syncDataXrayClassificationsNightly', MODEL_SYNC_VARS)
patchWorkflow('searchDataXray', SEARCH_VARS)

// --- Publish results for the form -------------------------------------------

def htmlSection = { String title, List items ->
    if (items.isEmpty()) { return '' }
    def lis = items.collect { "<li>${it}</li>" }.join('')
    return "<p><b>${title}:</b></p><ul>${lis}</ul>"
}

def detail = ''
detail += htmlSection('Created', created)
detail += htmlSection('Already present', skipped)
detail += htmlSection('Workflows configured', patched)
detail += htmlSection('Failed', failed)
if (detail.isEmpty()) { detail = '<p>Nothing to do.</p>' }

execution.setVariable('configureCreatedCount', created.size())
execution.setVariable('configureSkippedCount', skipped.size())
execution.setVariable('configurePatchedCount', patched.size())
execution.setVariable('configureFailedCount', failed.size())
execution.setVariable('configureDetail', detail)

loggerApi.info("Configure Data X-Ray complete: created=${created.size()}, " +
    "skipped=${skipped.size()}, configured=${patched.size()}, failed=${failed.size()}")
