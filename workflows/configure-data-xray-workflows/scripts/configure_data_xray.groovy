// configure_data_xray.groovy
//
// One-time, idempotent operating-model bootstrap for the Data X-Ray workflows,
// run by an admin from the + Create menu.
//
// What it does:
//   1. Ensures the community, domains, asset types, attribute types and the
//      "returns / returned by" relation type the Data X-Ray sync, search,
//      import and rerun workflows depend on all exist — creating any
//      that are missing with FIXED canonical UUIDs. Those UUIDs are the same
//      ones hardcoded as constants in the sync/search scripts and baked into
//      searchDataXrayForm.form's pickers, so a fresh instance lines up with no
//      edits — no per-workflow configuration is needed.
//   2. Ensures the custom attribute types are surfaced on the asset pages by
//      creating a per-type assignment — but only where one isn't already in
//      effect, so existing (hand-tuned) assignments are never disturbed.
//   3. Ensures the two access-control roles exist with FIXED canonical UUIDs:
//        - 'Data X-Ray Admin' — granted the WORKFLOW_ADMINISTRATION global
//          permission, so holders (alongside Sysadmins) can open each Data
//          X-Ray workflow's settings page and set its Data X-Ray connection settings
//          (Base URL + Bearer token; Edge edition: the Edge HTTP connection name).
//          NOTE: WORKFLOW_ADMINISTRATION is a *global* permission — it confers
//          admin over ALL workflows on the instance, not only the Data X-Ray
//          ones; Collibra has no per-workflow administration permission.
//        - 'Data X-Ray User' — a plain membership role, no permissions; it only
//          exists to gate who may *run* the search/sync workflows.
//   4. Sets each Data X-Ray workflow definition's start roles ("who can run"):
//        - searchDataXray / syncDataXrayClassifications / rerunDataXraySearch → User + Admin
//        - syncDataXrayClassificationsNightly / syncDataXrayFilesNightly /
//          configureDataXrayWorkflows → Admin
//      For rerunDataXraySearch it additionally sets exclusivity UNCONSTRAINED
//      (so an open rerun-summary task never hides the action or blocks the
//      nightly driver). The action cannot be scoped to the query asset type
//      (asset-type assignment rules reject global start roles), so the rerun
//      script hard-guards the asset type at runtime instead.
//      This is applied at runtime via workflowDefinitionApi (a partial update —
//      it does NOT touch the configuration variables, so admin-set tokens are
//      preserved). This runtime assignment is the ONLY thing that enforces start
//      access: the BPMN's flowable:candidateStarterGroups is inert in Collibra
//      (verified — a fresh deploy lands with no start-role restriction until this
//      runs). Start roles, like configuration variables, then PERSIST across an
//      in-place redeploy and reset only if a workflow is deleted and reimported.
//      Any target not yet deployed is skipped — re-run this workflow after
//      deploying the rest, and re-run it after any delete-then-reimport.
//
// The configuration variables (Base URL + Bearer token, or the Edge connection name) themselves carry NO
// per-role visibility — readable="false" already hides them from everyone who
// starts a workflow, and only Sysadmin / WORKFLOW_ADMINISTRATION holders can
// edit them on the settings page. Granting the Admin role that permission (3)
// is the only knob; nothing here "hides" the token from Users — it already is.
//
// The Data X-Ray workflows reference these elements by fixed UUID directly, so
// there are NO configuration variables to write — the admin only sets each
// workflow's Data X-Ray connection settings by hand.
//
// Idempotent: anything that already exists is left untouched. Safe to re-run.
//
// Operating-model elements are created/looked up via the meta APIs
// (assetTypeApi, attributeTypeApi, assignmentApi, statusApi) and the instance
// APIs (communityApi, domainApi). Every step is wrapped so a single failure is
// reported but never aborts the rest of the run.
//
// Process variables produced (for the results form):
//   configureCreatedCount  (int)    – elements created this run
//   configureSkippedCount  (int)    – elements already present, left untouched
//   configureFailedCount   (int)    – steps that errored
//   configureDetail        (String) – HTML breakdown of the above

import com.collibra.dgc.core.api.dto.instance.community.AddCommunityRequest
import com.collibra.dgc.core.api.dto.instance.domain.AddDomainRequest
import com.collibra.dgc.core.api.dto.meta.assettype.AddAssetTypeRequest
import com.collibra.dgc.core.api.dto.meta.attributetype.AddAttributeTypeRequest
import com.collibra.dgc.core.api.dto.meta.attributetype.AttributeKind
import com.collibra.dgc.core.api.dto.meta.relationtype.AddRelationTypeRequest
import com.collibra.dgc.core.api.dto.assignment.AddAssignmentRequest
import com.collibra.dgc.core.api.dto.assignment.ChangeAssignmentRequest
import com.collibra.dgc.core.api.dto.assignment.CharacteristicTypeAssignmentReference
import com.collibra.dgc.core.api.model.assignment.RelationTypeDirection
import com.collibra.dgc.core.api.model.meta.type.AssetTypeSymbolType
import com.collibra.dgc.core.api.model.meta.type.attribute.StringType
import com.collibra.dgc.core.api.dto.role.AddRoleRequest
import com.collibra.dgc.core.api.dto.role.ChangeRoleRequest
import com.collibra.dgc.core.api.model.security.Permission
import com.collibra.dgc.core.api.dto.workflow.ChangeWorkflowDefinitionRequest
import com.collibra.dgc.core.api.model.workflow.WorkflowExclusivity

// --- Canonical identifiers (fixed across instances) -------------------------

def COMMUNITY_ID   = '019c9fbf-0ab2-7258-b118-e68d3e814fe1'
def COMMUNITY_NAME = 'Collibra to Data X-Ray Integration'

// System "Data Asset Domain" domain type — present on every instance.
def DOMAIN_TYPE_ID = '00000000-0000-0000-0000-000000030001'

// System attribute type — present on every instance.
def DESCRIPTION_ATTR_ID = '00000000-0000-0000-0000-000000003114'

// Custom attribute type IDs.
def LINK_ATTR_ID        = '019c9fc5-aa4c-72af-8918-caa54fe61eba'
def SEARCH_LINK_ATTR_ID = '019c9fc5-8ff5-77a7-962d-4b6b05c69254'
def SUBTYPE_ATTR_ID     = '019c9fc5-ecc8-759b-9c0b-78547fa315ad'
def DXID_ATTR_ID        = '019e73ae-1aa8-700c-8086-626326822c22'
def INDEX_ID_ATTR_ID = '019e9211-4a7c-7b1e-9d3f-2c8e5f6a0b41'  // Data X-Ray Index ID (Edge edition: numeric id in DXR's search index)
def FILES_ATTR_ID       = '019e2736-8bd0-727a-b4ab-6899517a3e73'

// File-import model (gated asset import + rerun + nightly file sync). Same
// canonical-UUID scheme as everything above; the search/rerun scripts hardcode
// these as constants.
def FILES_DOMAIN_ID       = '019e9210-52a4-7c31-9b5e-3d8f0a6c1e42'
def FILE_TYPE_ID          = '019e9210-6e77-7b02-8c4a-92d15b7f30a9'
def FILE_PATH_ATTR_ID     = '019e9210-8a3c-70d5-b1e8-604f9c2d7a53'
def FILE_SIZE_ATTR_ID     = '019e9210-9bd0-7e46-a927-15c8e03b6f84'
def LAST_MODIFIED_ATTR_ID = '019e9210-ad15-73f8-bc06-7e94a1d52c37'
def DATASOURCE_ATTR_ID    = '019e9210-be62-7a89-90d3-48b6f57e0c21'
def QUERY_ATTR_ID         = '019e9210-cf9b-751a-85f2-d30c7a48b9e6'
def FILTER_ATTR_ID        = '019e9210-e04d-7c6b-a481-5f29d8036c7a'

// "returns / returned by" — links an Unstructured Data Query asset (source) to
// each Data X-Ray File asset (target) it currently returns. Deliberately NOT
// the system "groups" relation type (7017), which the query already uses for
// its classification criteria: a dedicated type makes "which files does this
// query return" a clean findRelations(typeId, sourceId) and lets the rerun
// workflow retire a file only once NO query returns it any more.
def RETURNS_RELTYPE_ID    = '019e9210-f180-79dc-b5a0-6c31e94f82d5'

// "searches text in / text searched by" — links an Unstructured Data Query
// asset (source) to each Annotator asset (target) whose annotations its
// annotated-text filter is searched in. Distinct from the query's "groups"
// criteria relations: those annotators are AND-ed requirements, these only
// scope the phrase (OR-ed among themselves). The rerun rebuilds the phrase
// clause from these relations, so annotator renames flow through.
def TEXT_FILTER_RELTYPE_ID = '019e9210-f2a1-7d3e-8c4b-5a6f7e8d9c01'

// System "groups / is grouped by" relation type — present on every instance.
// query→classification and file→classification links use it.
def GROUPS_RELTYPE_ID     = '00000000-0000-0000-0000-000000007017'

def QUERY_TYPE_ID         = '019dcf97-3bac-72c3-8b59-b6ddbe8a8396'

// System parent asset types — present on every instance.
def DATA_CONCEPT_TYPE_ID = '00000000-0000-0000-0000-000000031113'
def DATA_ASSET_TYPE_ID   = '00000000-0000-0000-0000-000000031002'

// --- Access-control roles (fixed canonical UUIDs) ---------------------------
// Created here if missing. 'Data X-Ray Admin' gets WORKFLOW_ADMINISTRATION so
// it can edit the Data X-Ray connection settings; 'Data X-Ray User' is a bare
// membership role used only to gate who can run the search/sync workflows.
def ROLE_ADMIN_ID   = '019e9000-abcd-7000-a115-74a17d050000'
def ROLE_ADMIN_NAME = 'Data X-Ray Admin'
def ROLE_USER_ID    = '019e9000-abce-7000-9e7d-a7a7115e0000'
def ROLE_USER_NAME  = 'Data X-Ray User'

// Which start roles ("who can run") each workflow definition should carry,
// keyed by its BPMN process id. Setting these by UUID at runtime is the sole
// enforcement (the BPMN's flowable:candidateStarterGroups is inert in Collibra).
def starterRoleDefs = [
    [processId: 'searchDataXray',                     name: 'Search Data X-Ray',
     roleIds: [ROLE_USER_ID, ROLE_ADMIN_ID]],
    [processId: 'syncDataXrayClassifications',        name: 'Sync Data X-Ray Classifications',
     roleIds: [ROLE_USER_ID, ROLE_ADMIN_ID]],
    [processId: 'syncDataXrayClassificationsNightly', name: 'Sync Data X-Ray Classifications (Nightly)',
     roleIds: [ROLE_ADMIN_ID]],
    // UNCONSTRAINED exclusivity: by default Collibra allows one running
    // instance per resource, which HIDES the workflow from the asset's action
    // menu while its rerun-summary task sits in someone's inbox (and would
    // make the nightly driver's start calls fail for queries with an open
    // summary). NOTE: the start action cannot be scoped to the query asset
    // type — asset-type assignment rules are rejected (workflowWrongRoles)
    // for workflows whose start roles are GLOBAL roles, and our access model
    // is global roles by design. rerun_collector.groovy therefore hard-guards
    // the asset type at runtime instead.
    [processId: 'rerunDataXraySearch',                name: 'Rerun Data X-Ray Search',
     roleIds: [ROLE_USER_ID, ROLE_ADMIN_ID],
     exclusivity: 'UNCONSTRAINED'],
    [processId: 'syncDataXrayFilesNightly',           name: 'Sync Data X-Ray Files (Nightly)',
     roleIds: [ROLE_ADMIN_ID]],
    [processId: 'configureDataXrayWorkflows',         name: 'Configure Data X-Ray Workflows',
     roleIds: [ROLE_ADMIN_ID]],
]

def domainDefs = [
    [id: '019c9fbf-622c-76f4-9dd6-2a9730a11515', name: 'Data X-Ray Classifications'],
    [id: '019dcf96-233a-72e1-bf25-8398b8c9146e', name: 'Data X-Ray Custom Queries'],
    [id: FILES_DOMAIN_ID,                        name: 'Data X-Ray Files'],
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
    // displayName enabled: imported file assets carry a uniqueness suffix in
    // their full name (Collibra names are unique per domain and 10k files WILL
    // contain duplicate filenames), so the UI shows the clean display name.
    [id: FILE_TYPE_ID, name: 'Data X-Ray File',
     parent: DATA_ASSET_TYPE_ID, symbol: 'ACRONYM_CODE', color: '#d97e3e',
     icon: null, acronym: 'FILE', displayName: true],
]

def attrTypeDefs = [
    [id: LINK_ATTR_ID,          name: 'Link',                  stringType: 'PLAIN_TEXT'],
    [id: SEARCH_LINK_ATTR_ID,   name: 'Search Link',           stringType: 'PLAIN_TEXT'],
    [id: SUBTYPE_ATTR_ID,       name: 'Sub Type',              stringType: 'PLAIN_TEXT'],
    [id: DXID_ATTR_ID,          name: 'Data X-Ray ID',         stringType: 'PLAIN_TEXT'],
    [id: INDEX_ID_ATTR_ID,      name: 'Data X-Ray Index ID',   stringType: 'PLAIN_TEXT'],
    [id: FILES_ATTR_ID,         name: 'Files',                 stringType: 'RICH_TEXT'],
    [id: FILE_PATH_ATTR_ID,     name: 'File Path',             stringType: 'PLAIN_TEXT'],
    [id: FILE_SIZE_ATTR_ID,     name: 'File Size',             stringType: 'PLAIN_TEXT'],
    [id: LAST_MODIFIED_ATTR_ID, name: 'Last Modified',         stringType: 'PLAIN_TEXT'],
    [id: DATASOURCE_ATTR_ID,    name: 'Datasource Name',       stringType: 'PLAIN_TEXT'],
    [id: QUERY_ATTR_ID,         name: 'Data X-Ray Query',      stringType: 'PLAIN_TEXT'],
    [id: FILTER_ATTR_ID,        name: 'Annotated Text Filter', stringType: 'PLAIN_TEXT'],
]

// Which custom attribute types each asset type should surface on its page.
def assignmentDefs = [
    [assetTypeId: '01965d43-235d-796b-be49-078f91d7472a', name: 'Classification',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID, INDEX_ID_ATTR_ID]],
    [assetTypeId: '01922a69-e7a0-7ac7-a581-c9ba9286ccf1', name: 'Annotator',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID, INDEX_ID_ATTR_ID],
     relations: [[id: TEXT_FILTER_RELTYPE_ID, direction: 'TO_SOURCE']]], // text searched by queries
    [assetTypeId: '019c9fbd-a91b-7242-9451-79ab632163a3', name: 'Extractor',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID, INDEX_ID_ATTR_ID]],
    [assetTypeId: '019c9fbe-25c3-71b7-90ac-057dd582fa1e', name: 'Label',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, DXID_ATTR_ID, INDEX_ID_ATTR_ID]],
    // relations: relation types surfaced on the asset page (the page only
    // renders characteristics that are IN the assignment — relations written
    // via the API exist regardless, but stay invisible without this).
    // TO_TARGET = this type is the relation's source (page shows the role);
    // TO_SOURCE = this type is the target (page shows the co-role).
    [assetTypeId: '019dcf97-3bac-72c3-8b59-b6ddbe8a8396', name: 'Unstructured Data Query',
     customAttrs: [LINK_ATTR_ID, SEARCH_LINK_ATTR_ID, SUBTYPE_ATTR_ID, FILES_ATTR_ID, DXID_ATTR_ID,
                   QUERY_ATTR_ID, FILTER_ATTR_ID],
     relations: [[id: GROUPS_RELTYPE_ID,      direction: 'TO_TARGET'],   // groups classifications
                 [id: RETURNS_RELTYPE_ID,     direction: 'TO_TARGET'],   // returns files
                 [id: TEXT_FILTER_RELTYPE_ID, direction: 'TO_TARGET']]], // searches text in annotators
    // descriptionMin 0: imported file assets carry no Description, so a
    // mandatory Description would flag every one of them as incomplete.
    [assetTypeId: FILE_TYPE_ID, name: 'Data X-Ray File', descriptionMin: 0,
     customAttrs: [FILE_PATH_ATTR_ID, FILE_SIZE_ATTR_ID, LAST_MODIFIED_ATTR_ID,
                   DATASOURCE_ATTR_ID, LINK_ATTR_ID, DXID_ATTR_ID],
     relations: [[id: GROUPS_RELTYPE_ID,  direction: 'TO_TARGET'],   // groups matched classifications
                 [id: RETURNS_RELTYPE_ID, direction: 'TO_SOURCE']]], // returned by queries
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

// --- Result accumulators ----------------------------------------------------

def created    = []
def skipped    = []
def failed     = []
def configured = []   // run-access (start roles) applied this run; idempotent

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
                .displayNameEnabled(t.displayName == true)
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

// --- Phase 4b: relation type -------------------------------------------------

// "Unstructured Data Query returns Data X-Ray File". Created with a fixed
// canonical UUID like everything else; the search/rerun scripts reference it
// as a constant.
try {
    def rtid = string2Uuid(RETURNS_RELTYPE_ID)
    if (relationTypeApi.exists(rtid)) {
        skipped << "Relation type 'returns / returned by'"
    } else {
        relationTypeApi.addRelationType(AddRelationTypeRequest.builder()
            .id(rtid)
            .sourceTypeId(string2Uuid(QUERY_TYPE_ID))
            .role('returns')
            .targetTypeId(string2Uuid(FILE_TYPE_ID))
            .coRole('returned by')
            .build())
        created << "Relation type 'returns / returned by'"
        loggerApi.info("Created relation type returns/returned by [${RETURNS_RELTYPE_ID}]")
    }
} catch (Exception e) {
    failed << "Relation type 'returns / returned by': ${e.message}"
    loggerApi.error("ensure relation type failed: ${e.message}")
}

// "Unstructured Data Query searches text in Annotator" — scopes the query's
// annotated-text filter to specific annotators (see TEXT_FILTER_RELTYPE_ID).
try {
    def rtid = string2Uuid(TEXT_FILTER_RELTYPE_ID)
    if (relationTypeApi.exists(rtid)) {
        skipped << "Relation type 'searches text in / text searched by'"
    } else {
        relationTypeApi.addRelationType(AddRelationTypeRequest.builder()
            .id(rtid)
            .sourceTypeId(string2Uuid(QUERY_TYPE_ID))
            .role('searches text in')
            .targetTypeId(string2Uuid('01922a69-e7a0-7ac7-a581-c9ba9286ccf1'))
            .coRole('text searched by')
            .build())
        created << "Relation type 'searches text in / text searched by'"
        loggerApi.info("Created relation type searches text in/text searched by [${TEXT_FILTER_RELTYPE_ID}]")
    }
} catch (Exception e) {
    failed << "Relation type 'searches text in / text searched by': ${e.message}"
    loggerApi.error("ensure text-filter relation type failed: ${e.message}")
}

// --- Phase 5: assignments (best-effort, never disturb existing) -------------

// Resolve the standard statuses once; only keep those that exist here.
def statusUuids = STANDARD_STATUS_IDS
    .findAll { statusApi.exists(string2Uuid(it)) }
    .collect { string2Uuid(it) }

// Augment an assignment the asset type already owns with any of the spec's
// custom attribute types it doesn't yet surface. The characteristic list is
// rebuilt faithfully from the assignment's own typed reference lists
// (attributes, relations, complex relations — with min/max and relation
// direction/restriction preserved) and the missing attributes appended.
// Assignments carrying articulation or validation rules are left alone:
// changeAssignment replaces those lists too, and wiping hand-tuned rules is
// worse than an attribute not showing on the asset page (the workflows write
// attributes via the API regardless of assignment).
def augmentOwnedAssignment = { own, spec, List missingAttrUuids, List missingRelSpecs ->
    if (!(own.getArticulationRules() ?: []).isEmpty() || !(own.getValidationRules() ?: []).isEmpty()) {
        skipped << "Assignment for '${spec.name}' (has hand-tuned rules — add the new characteristic(s) in the UI)"
        loggerApi.warn("Assignment for ${spec.name} carries articulation/validation rules; not auto-augmenting")
        return
    }
    def refs = []
    def copyRefs = { list, String discriminator ->
        (list ?: []).each { r ->
            def b = CharacteristicTypeAssignmentReference.builder()
                .id(r.getAssignedResourceReference().getId())
                .resourceDiscriminator(discriminator)
                .min(r.getMinimumOccurrences())
                .max(r.getMaximumOccurrences())
            if (r.getRelationTypeDirection() != null) {
                b.relationTypeDirection(r.getRelationTypeDirection())
                if (r.getRelationTypeRestriction() != null) {
                    b.relationTypeRestriction(r.getRelationTypeRestriction().getId())
                }
            }
            refs << b.build()
        }
    }
    copyRefs(own.getAssignedAttributeTypeReferences(),       'AttributeType')
    copyRefs(own.getAssignedRelationTypeReferences(),        'RelationType')
    copyRefs(own.getAssignedComplexRelationTypeReferences(), 'ComplexRelationType')
    missingAttrUuids.each { attrId ->
        refs << CharacteristicTypeAssignmentReference.builder()
            .id(attrId)
            .resourceDiscriminator('AttributeType')
            .min(0)
            .build()
    }
    missingRelSpecs.each { rel ->
        refs << CharacteristicTypeAssignmentReference.builder()
            .id(string2Uuid(rel.id))
            .resourceDiscriminator('RelationType')
            .relationTypeDirection(RelationTypeDirection.valueOf(rel.direction))
            .min(0)
            .build()
    }
    def changeReq = ChangeAssignmentRequest.builder()
        .assignmentId(own.getId())
        .statusIds(own.getStatuses().collect { it.getId() })
        .characteristicTypes(refs)
    def domainTypeIds = (own.getDomainTypes() ?: []).collect { it.getId() }
    if (!domainTypeIds.isEmpty()) { changeReq.domainTypeIds(domainTypeIds) }
    assignmentApi.changeAssignment(changeReq.build())
    created << "Assignment for '${spec.name}' — surfaced ${missingAttrUuids.size()} attribute / ${missingRelSpecs.size()} relation type(s)"
    loggerApi.info("Augmented assignment for ${spec.name}: +${missingAttrUuids.size()} attribute, +${missingRelSpecs.size()} relation type(s)")
}

assignmentDefs.each { spec ->
    try {
        def tid = string2Uuid(spec.assetTypeId)
        def applicable = assignmentApi.getAssignmentsForAssetType(tid)

        // If this asset type already owns an assignment, augment it with any
        // custom attributes it doesn't yet surface (no-op when complete).
        def own = applicable.find { it.getAssetType().getId() == tid }
        if (own != null) {
            def ownAttrIds = [] as Set
            own.getAssignedAttributeTypeReferences().each { ref ->
                ownAttrIds << ref.getAssignedResourceReference().getId()
            }
            def ownRelIds = [] as Set
            own.getAssignedRelationTypeReferences().each { ref ->
                ownRelIds << ref.getAssignedResourceReference().getId()
            }
            def missingOnOwn = spec.customAttrs.collect { string2Uuid(it) }.findAll { !ownAttrIds.contains(it) }
            def missingRels = (spec.relations ?: []).findAll { !ownRelIds.contains(string2Uuid(it.id)) }
            if (missingOnOwn.isEmpty() && missingRels.isEmpty()) {
                skipped << "Assignment for '${spec.name}' (already has its own)"
            } else {
                augmentOwnedAssignment(own, spec, missingOnOwn, missingRels)
            }
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

        // Create an own assignment: Description + custom attributes. Description
        // is mandatory (min 1) unless the spec relaxes it — file assets carry no
        // description, so theirs is optional.
        def descriptionMin = spec.containsKey('descriptionMin') ? spec.descriptionMin : 1
        def refs = []
        refs << CharacteristicTypeAssignmentReference.builder()
            .id(string2Uuid(DESCRIPTION_ATTR_ID))
            .resourceDiscriminator('AttributeType')
            .min(descriptionMin)
            .build()
        spec.customAttrs.each { attrId ->
            refs << CharacteristicTypeAssignmentReference.builder()
                .id(string2Uuid(attrId))
                .resourceDiscriminator('AttributeType')
                .min(0)
                .build()
        }
        (spec.relations ?: []).each { rel ->
            refs << CharacteristicTypeAssignmentReference.builder()
                .id(string2Uuid(rel.id))
                .resourceDiscriminator('RelationType')
                .relationTypeDirection(RelationTypeDirection.valueOf(rel.direction))
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

// --- Phase 6: access-control roles ------------------------------------------

// Create a role if missing; if present, ensure it carries the required global
// permissions (adding any that are absent without disturbing existing ones).
def ensureRole = { String roleId, String roleName, List requiredPerms ->
    try {
        def rid = string2Uuid(roleId)
        if (roleApi.exists(rid)) {
            if (requiredPerms.isEmpty()) {
                skipped << "Role '${roleName}'"
                return
            }
            def current = roleApi.getRole(rid).getPermissions() ?: []
            def missing = requiredPerms.findAll { !current.contains(it) }
            if (missing.isEmpty()) {
                skipped << "Role '${roleName}'"
            } else {
                def merged = (current + missing).unique()
                roleApi.changeRole(ChangeRoleRequest.builder()
                    .id(rid).name(roleName).permissions(merged).build())
                configured << "Role '${roleName}' — granted ${missing.collect { it.name() }.join(', ')}"
                loggerApi.info("Granted ${missing} to role ${roleName} [${roleId}]")
            }
        } else {
            def b = AddRoleRequest.builder().id(rid).name(roleName).global(true)
            if (!requiredPerms.isEmpty()) { b.permissions(requiredPerms) }
            roleApi.addRole(b.build())
            created << "Role '${roleName}'"
            loggerApi.info("Created role ${roleName} [${roleId}]")
        }
    } catch (Exception e) {
        failed << "Role '${roleName}': ${e.message}"
        loggerApi.error("ensure role ${roleName} failed: ${e.message}")
    }
}

ensureRole(ROLE_ADMIN_ID, ROLE_ADMIN_NAME, [Permission.WORKFLOW_ADMINISTRATION])
ensureRole(ROLE_USER_ID,  ROLE_USER_NAME,  [])

// --- Phase 7: workflow run-access (start roles) -----------------------------

// Set each Data X-Ray workflow definition's start roles by UUID. This is a
// partial update: only startRoleIds is sent, so configuration variables (the
// admin-set Data X-Ray connection settings) are left untouched. A workflow that isn't
// deployed yet is skipped — re-run this once everything is deployed.
starterRoleDefs.each { s ->
    try {
        def wd = workflowDefinitionApi.getWorkflowDefinitionByProcessId(s.processId)
        if (wd == null) {
            skipped << "Run-access for '${s.name}' (not deployed yet)"
            return
        }
        def roleUuids = s.roleIds.collect { string2Uuid(it) }
        def change = ChangeWorkflowDefinitionRequest.builder()
            .id(wd.getId())
            .startRoleIds(roleUuids)
        if (s.exclusivity) { change.exclusivity(WorkflowExclusivity.valueOf(s.exclusivity)) }
        workflowDefinitionApi.changeWorkflowDefinition(change.build())
        def who = s.roleIds.contains(ROLE_USER_ID) ? 'User + Admin' : 'Admin only'
        configured << "Run-access for '${s.name}' → ${who}${s.exclusivity ? " (${s.exclusivity})" : ''}"
        loggerApi.info("Set start roles for ${s.processId}: ${roleUuids}${s.exclusivity ? ", exclusivity ${s.exclusivity}" : ''}")
    } catch (Exception e) {
        // getWorkflowDefinitionByProcessId throws if the definition is absent.
        skipped << "Run-access for '${s.name}' (not deployed yet)"
        loggerApi.warn("set run-access ${s.name} skipped: ${e.message}")
    }
}

// --- Publish results for the form -------------------------------------------

def htmlSection = { String title, List items ->
    if (items.isEmpty()) { return '' }
    def lis = items.collect { "<li>${it}</li>" }.join('')
    return "<p><b>${title}:</b></p><ul>${lis}</ul>"
}

def detail = ''
detail += htmlSection('Created', created)
detail += htmlSection('Access &amp; run roles configured', configured)
detail += htmlSection('Already present', skipped)
detail += htmlSection('Failed', failed)
if (detail.isEmpty()) { detail = '<p>Nothing to do.</p>' }

execution.setVariable('configureCreatedCount', created.size())
execution.setVariable('configureSkippedCount', skipped.size())
execution.setVariable('configureFailedCount', failed.size())
execution.setVariable('configureDetail', detail)

loggerApi.info("Configure Data X-Ray complete: created=${created.size()}, " +
    "skipped=${skipped.size()}, failed=${failed.size()}")
