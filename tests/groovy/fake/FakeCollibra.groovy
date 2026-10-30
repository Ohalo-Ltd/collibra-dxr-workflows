package fake

// An in-memory Collibra for the offline tests: assets, attributes, relations,
// tags, the operating-model ids the scripts check with exists(), workflow
// definitions, started instances and attachments, behind fakes of the Java API
// objects the DataXray scripts use (assetApi, relationApi, attributeApi, …).
//
// Collibra behaviour the fake reproduces, because the scripts depend on it
// (checked against ohalo.collibra.com and the 32.1.43 Javadoc, Aug–Oct 2026):
//   - Asset names are unique per domain; addAsset/changeAsset refuse a clash.
//   - A relation is unique per (source, target, type): adding it again throws.
//     addRelations is all-or-nothing, which is why processFileBatch falls back
//     to adding one by one.
//   - Every write records the acting user as createdBy. Asynchronous script
//     tasks run as the Workflow user (00000000-0000-0000-0000-000000900003);
//     a person's links are seeded with asPerson { }.
//   - getAsset / removeRelation of an unknown id throw.
//   - find* pages: cursor('') starts cursor paging and nextCursor is null after
//     the last page; without a cursor, limit(n) returns the first n.
//   - setAssetAttributes replaces every value of that type on the asset; an
//     empty list clears it.
//   - A new asset's status defaults to Candidate.
//   - Removing an asset removes its attributes and relations (Collibra deletes
//     them with it), so a deleted query takes its returns links along.
// Not modelled: permissions, responsibilities, attribute value types, single-
// vs multi-value attribute rules, the operating model's assignments. A filter
// or builder field the fake does not implement throws, so a test can never
// pass by silently ignoring it.
class FakeCollibra {
    static final UUID WORKFLOW_USER = UUID.fromString('00000000-0000-0000-0000-000000900003')
    static final UUID PERSON = UUID.fromString('5e5e5e5e-0000-4000-8000-00000000beef')
    static final UUID CANDIDATE = UUID.fromString('00000000-0000-0000-0000-000000005008')
    static final UUID OBSOLETE = UUID.fromString('00000000-0000-0000-0000-000000005011')
    // Statuses a person sets in Collibra. Fake ids: the scripts only ever ask
    // "is this one of mine?", so any id that is not the integration's will do.
    static final UUID ACCEPTED = UUID.nameUUIDFromBytes('fake-status:Accepted'.bytes)
    static final UUID UNDER_REVIEW = UUID.nameUUIDFromBytes('fake-status:Under Review'.bytes)

    // --- store (plain maps, so a snapshot is a deep copy) ----------------------
    Map<UUID, Map> assets = [:]
    Map<UUID, Map> attributes = [:]
    Map<UUID, Map> relations = [:]
    Map<UUID, String> domains = [:]        // domain id → name
    Map<UUID, String> assetTypes = [:]     // asset type id → name
    Map<UUID, String> attributeTypes = [:]
    Map<UUID, String> relationTypes = [:]
    Map<UUID, String> statuses = [:]
    Map<String, Map> workflowDefinitions = [:]   // process id → [id:, name:]
    List<Map> startedInstances = []
    List<Map> attachments = []
    long seq = 0

    UUID actor = WORKFLOW_USER
    Integer maxPageSize = null              // cap any find* page (forces multi-page walks)
    List<List> calls = []                    // [api.method, args]
    Map<String, List<Closure>> beforeHooks = [:].withDefault { [] }
    Map<String, List<Throwable>> failures = [:].withDefault { [] }

    def assetApi = new FakeAssetApi(f: this)
    def relationApi = new FakeRelationApi(f: this)
    def attributeApi = new FakeAttributeApi(f: this)
    def attributeTypeApi = new FakeExistsApi(f: this, name: 'attributeTypeApi', store: { attributeTypes })
    def statusApi = new FakeExistsApi(f: this, name: 'statusApi', store: { statuses })
    def relationTypeApi = new FakeExistsApi(f: this, name: 'relationTypeApi', store: { relationTypes })
    def assetTypeApi = new FakeExistsApi(f: this, name: 'assetTypeApi', store: { assetTypes })
    def workflowDefinitionApi = new FakeWorkflowDefinitionApi(f: this)
    def workflowInstanceApi = new FakeWorkflowInstanceApi(f: this)
    def attachmentApi = new FakeAttachmentApi(f: this)

    // --- test controls ------------------------------------------------------------

    UUID newId() { UUID.nameUUIDFromBytes("fake-id:${++seq}".toString().bytes) }

    /** Run `body` with writes recorded as made by a person, not the Workflow user. */
    def asPerson(UUID who = PERSON, Closure body) {
        UUID before = actor
        actor = who
        try { return body() } finally { actor = before }
    }

    /** The next call of `method` (e.g. 'relationApi.addRelations') throws `ex`. */
    void failNext(String method, Throwable ex = new RuntimeException("fake: ${method} refused")) { failures[method] << ex }

    /** Run `hook(args)` before every call of `method`. */
    void before(String method, Closure hook) { beforeHooks[method] << hook }

    int callCount(String method) { calls.count { it[0] == method } }

    void call(String method, Object... args) {
        calls << [method, args.toList()]
        beforeHooks[method].each { it.call(args.toList()) }
        if (!failures[method].isEmpty()) { throw failures[method].remove(0) }
    }

    Map snapshot() {
        [assets: deepCopy(assets), attributes: deepCopy(attributes), relations: deepCopy(relations),
         startedInstances: startedInstances.collect { new LinkedHashMap(it) },
         attachments: attachments.collect { new LinkedHashMap(it) }, seq: seq]
    }

    void restore(Map snap) {
        assets = deepCopy(snap.assets); attributes = deepCopy(snap.attributes); relations = deepCopy(snap.relations)
        startedInstances = snap.startedInstances.collect { new LinkedHashMap(it) }
        attachments = snap.attachments.collect { new LinkedHashMap(it) }
        seq = snap.seq
    }

    private static Map deepCopy(Map<UUID, Map> m) {
        def out = new LinkedHashMap()
        m.each { k, v ->
            def copy = new LinkedHashMap(v)
            if (copy.tags instanceof Set) { copy.tags = new LinkedHashSet(copy.tags) }
            out[k] = copy
        }
        return out
    }

    // --- model seeding --------------------------------------------------------------

    /** Register the operating model of dxrModelIds() as Configure creates it. */
    FakeCollibra seedModel(Map ids) {
        domains[ids.queryDomainId] = 'DataXray Sync Filter'
        domains[ids.classificationsDomainId] = 'DataXray Classifications'
        domains[ids.filesDomainId] = 'DataXray Files'
        assetTypes[ids.queryAssetTypeId] = 'DataXray Sync Filter'
        assetTypes[ids.fileTypeId] = 'DataXray File'
        assetTypes[ids.classificationTypeId] = 'Classification'
        assetTypes[ids.annotatorTypeId] = 'Annotator'
        assetTypes[ids.extractorTypeId] = 'Extractor'
        assetTypes[ids.labelTypeId] = 'Label'
        ids.each { String key, value ->
            // DataXray Extracted Values exists only while Configure's syncExtractorValues
            // is on (off by default): a test turns it on with attributeTypes[…] = ….
            if (key.endsWith('AttrTypeId') && key != 'extractedValuesAttrTypeId') { attributeTypes[(UUID) value] = key - 'AttrTypeId' }
            if (key.endsWith('RelationTypeId') || key.endsWith('RelTypeId')) { relationTypes[(UUID) value] = key - 'TypeId' }
        }
        statuses[CANDIDATE] = 'Candidate'
        statuses[OBSOLETE] = 'Obsolete'
        statuses[ACCEPTED] = 'Accepted'
        statuses[UNDER_REVIEW] = 'Under Review'
        statuses[ids.notInAnySearchStatusId] = 'Not in any search'
        statuses[ids.notInDataXrayStatusId] = 'Not in DataXray'
        return this
    }

    // --- store operations (also used by the test kit to seed data) --------------------

    Map requireAsset(UUID id) {
        def a = assets[id]
        if (a == null) { throw new FakeNotFound("Asset with id '${id}' not found") }
        return a
    }

    Map insertAsset(Map p) {
        requireProps('AddAssetRequest', p, ['name', 'domainId'])
        UUID id = (UUID) (p.id ?: newId())
        if (assets.containsKey(id)) { throw new FakeConflict("An asset with id '${id}' already exists") }
        if (!domains.containsKey(p.domainId)) { throw new FakeNotFound("Domain '${p.domainId}' not found") }
        if (p.typeId != null && !assetTypes.containsKey(p.typeId)) { throw new FakeNotFound("Asset type '${p.typeId}' not found") }
        checkNameFree((UUID) p.domainId, p.name.toString(), null)
        def a = [id: id, name: p.name.toString(), displayName: (p.displayName ?: p.name).toString(), domainId: p.domainId,
                 typeId: p.typeId, statusId: p.statusId ?: CANDIDATE, tags: [] as LinkedHashSet, createdBy: actor, seq: ++seq]
        assets[id] = a
        return a
    }

    void checkNameFree(UUID domainId, String name, UUID except) {
        def clash = assets.values().find { it.domainId == domainId && it.name == name && it.id != except }
        if (clash) { throw new FakeConflict("An asset named '${name}' already exists in domain '${domains[domainId]}'") }
    }

    Map insertRelation(UUID sourceId, UUID targetId, UUID typeId) {
        requireAsset(sourceId)
        requireAsset(targetId)
        if (!relationTypes.containsKey(typeId)) { throw new FakeNotFound("Relation type '${typeId}' not found") }
        def dup = relations.values().find { it.sourceId == sourceId && it.targetId == targetId && it.typeId == typeId }
        if (dup) { throw new FakeConflict("Relation already exists: ${sourceId} -[${relationTypes[typeId]}]-> ${targetId}") }
        def r = [id: newId(), sourceId: sourceId, targetId: targetId, typeId: typeId, createdBy: actor, seq: ++seq]
        relations[r.id] = r
        return r
    }

    List<Map> setAttributeValues(UUID assetId, UUID typeId, List values) {
        requireAsset(assetId)
        if (!attributeTypes.containsKey(typeId)) { throw new FakeNotFound("Attribute type '${typeId}' not found") }
        attributes.values().findAll { it.assetId == assetId && it.typeId == typeId }.collect { it.id }.each { attributes.remove(it) }
        return values.collect { v ->
            def at = [id: newId(), assetId: assetId, typeId: typeId, value: v, seq: ++seq]
            attributes[at.id] = at
            at
        }
    }

    void deleteAsset(UUID id) {
        requireAsset(id)
        assets.remove(id)
        attributes.values().findAll { it.assetId == id }.collect { it.id }.each { attributes.remove(it) }
        relations.values().findAll { it.sourceId == id || it.targetId == id }.collect { it.id }.each { relations.remove(it) }
    }

    static void requireProps(String what, Map p, List<String> names) {
        names.each { n -> if (p[n] == null) { throw new IllegalArgumentException("${what}: ${n} is required") } }
    }

    /** Reject builder fields a fake find* does not implement. */
    static void onlyProps(String what, Map p, Collection<String> supported) {
        def extra = p.keySet() - supported
        if (extra) { throw new UnsupportedOperationException("fake ${what}: ${extra} not implemented") }
    }

    FPage page(List items, Map p) {
        int offset = p.cursor ? (p.cursor as int) : 0
        int limit = p.limit ? (p.limit as int) : 1000
        if (maxPageSize != null) { limit = Math.min(limit, maxPageSize) }
        def slice = items.drop(offset).take(limit)
        String next = (p.containsKey('cursor') && offset + limit < items.size()) ? (offset + limit).toString() : null
        return new FPage(results: slice, nextCursor: next, total: items.size())
    }

    // --- views (what the API returns: snapshots, never the stored maps) ---------------

    FRef statusRef(UUID id) { id == null ? null : new FRef(id: id, name: statuses[id] ?: id.toString()) }

    FAsset assetView(Map a) {
        new FAsset(id: a.id, name: a.name, displayName: a.displayName, createdBy: a.createdBy,
                   status: statusRef(a.statusId),
                   type: a.typeId == null ? null : new FRef(id: a.typeId, name: assetTypes[a.typeId]),
                   domain: new FRef(id: a.domainId, name: domains[a.domainId]))
    }

    FRelation relationView(Map r) {
        new FRelation(id: r.id, createdBy: r.createdBy,
                      source: new FRef(id: r.sourceId, name: assets[r.sourceId]?.name),
                      target: new FRef(id: r.targetId, name: assets[r.targetId]?.name),
                      type: new FRef(id: r.typeId, name: relationTypes[r.typeId]))
    }

    FAttribute attributeView(Map at) {
        new FAttribute(id: at.id, value: at.value,
                       asset: new FRef(id: at.assetId, name: assets[at.assetId]?.name),
                       type: new FRef(id: at.typeId, name: attributeTypes[at.typeId]))
    }

    // --- convenience reads for assertions -----------------------------------------------

    List<Map> relationsOf(UUID typeId, Map where = [:]) {
        relations.values().findAll { r ->
            r.typeId == typeId && (where.source == null || r.sourceId == where.source) &&
                (where.target == null || r.targetId == where.target)
        }.sort { it.seq } as List
    }

    List attributeValues(UUID assetId, UUID typeId) {
        attributes.values().findAll { it.assetId == assetId && it.typeId == typeId }.sort { it.seq }.collect { it.value }
    }

    UUID statusOf(UUID assetId) { requireAsset(assetId).statusId }
}

class FakeNotFound extends RuntimeException { FakeNotFound(String m) { super(m) } }

class FakeConflict extends RuntimeException { FakeConflict(String m) { super(m) } }

class FRef {
    UUID id
    String name
    String toString() { "${name}[${id}]" }
}

class FAsset {
    UUID id
    String name
    String displayName
    FRef status
    FRef type
    FRef domain
    UUID createdBy
}

class FRelation {
    UUID id
    FRef source
    FRef target
    FRef type
    UUID createdBy
}

class FAttribute {
    UUID id
    FRef asset
    FRef type
    Object value
}

class FPage {
    List results
    String nextCursor
    long total
}

class FTag {
    String name
}

class FDefinition {
    UUID id
    String processId
    String name
}

class FakeExistsApi {
    FakeCollibra f
    String name
    Closure<Map> store

    boolean exists(UUID id) {
        f.call("${name}.exists".toString(), id)
        return store.call().containsKey(id)
    }
}

class FakeAssetApi {
    FakeCollibra f

    boolean exists(UUID id) {
        f.call('assetApi.exists', id)
        return f.assets.containsKey(id)
    }

    FAsset getAsset(UUID id) {
        f.call('assetApi.getAsset', id)
        return f.assetView(f.requireAsset(id))
    }

    FAsset addAsset(Dto req) {
        f.call('assetApi.addAsset', req)
        return f.assetView(f.insertAsset(req.props))
    }

    List<FAsset> addAssets(List<Dto> reqs) {
        f.call('assetApi.addAssets', reqs)
        // All or nothing, like the bulk endpoint.
        def snap = f.snapshot()
        try {
            return reqs.collect { f.assetView(f.insertAsset(it.props)) }
        } catch (Exception ex) {
            f.restore(snap)
            throw ex
        }
    }

    FAsset changeAsset(Dto req) {
        f.call('assetApi.changeAsset', req)
        def p = req.props
        FakeCollibra.onlyProps('changeAsset', p, ['id', 'name', 'displayName', 'statusId', 'domainId', 'typeId'])
        def a = f.requireAsset((UUID) p.id)
        if (p.statusId != null && !f.statuses.containsKey(p.statusId)) { throw new FakeNotFound("Status '${p.statusId}' not found") }
        if (p.domainId != null && !f.domains.containsKey(p.domainId)) { throw new FakeNotFound("Domain '${p.domainId}' not found") }
        def newName = (p.name ?: a.name).toString()
        def newDomain = (UUID) (p.domainId ?: a.domainId)
        if (newName != a.name || newDomain != a.domainId) { f.checkNameFree(newDomain, newName, a.id) }
        a.name = newName
        a.domainId = newDomain
        if (p.displayName != null) { a.displayName = p.displayName.toString() }
        if (p.statusId != null) { a.statusId = p.statusId }
        if (p.typeId != null) { a.typeId = p.typeId }
        return f.assetView(a)
    }

    void removeAsset(UUID id) {
        f.call('assetApi.removeAsset', id)
        f.deleteAsset(id)
    }

    List<FAttribute> setAssetAttributes(Dto req) {
        f.call('assetApi.setAssetAttributes', req)
        def p = req.props
        FakeCollibra.requireProps('SetAssetAttributesRequest', p, ['assetId', 'typeId', 'values'])
        return f.setAttributeValues((UUID) p.assetId, (UUID) p.typeId, (List) p.values).collect { f.attributeView(it) }
    }

    FPage findAssets(Dto req) {
        f.call('assetApi.findAssets', req)
        def p = req.props
        FakeCollibra.onlyProps('findAssets', p, ['typeIds', 'domainId', 'statusIds', 'tagNames', 'name', 'limit', 'cursor', 'excludeMeta', 'typeInheritance'])
        def hits = f.assets.values().findAll { a ->
            (p.typeIds == null || (p.typeIds as Collection).contains(a.typeId)) &&
                (p.domainId == null || a.domainId == p.domainId) &&
                (p.statusIds == null || (p.statusIds as Collection).contains(a.statusId)) &&
                (p.tagNames == null || (p.tagNames as Collection).any { a.tags.contains(it) }) &&
                (p.name == null || a.name == p.name)
        }.sort { it.seq }
        return f.page(hits.collect { f.assetView(it) }, p)
    }

    List<FTag> getAssetTags(UUID id) {
        f.call('assetApi.getAssetTags', id)
        return f.requireAsset(id).tags.collect { new FTag(name: it) }
    }

    void addAssetTags(Dto req) {
        f.call('assetApi.addAssetTags', req)
        f.requireAsset((UUID) req.props.assetId).tags.addAll(req.props.tagNames as Collection)
    }

    void removeAssetTags(Dto req) {
        f.call('assetApi.removeAssetTags', req)
        f.requireAsset((UUID) req.props.assetId).tags.removeAll(req.props.tagNames as Collection)
    }
}

class FakeRelationApi {
    FakeCollibra f

    FRelation addRelation(Dto req) {
        f.call('relationApi.addRelation', req)
        def p = req.props
        FakeCollibra.requireProps('AddRelationRequest', p, ['sourceId', 'targetId', 'typeId'])
        return f.relationView(f.insertRelation((UUID) p.sourceId, (UUID) p.targetId, (UUID) p.typeId))
    }

    List<FRelation> addRelations(List<Dto> reqs) {
        f.call('relationApi.addRelations', reqs)
        def snap = f.snapshot()
        try {
            return reqs.collect { req ->
                def p = req.props
                FakeCollibra.requireProps('AddRelationRequest', p, ['sourceId', 'targetId', 'typeId'])
                f.relationView(f.insertRelation((UUID) p.sourceId, (UUID) p.targetId, (UUID) p.typeId))
            }
        } catch (Exception ex) {
            f.restore(snap)
            throw ex
        }
    }

    void removeRelation(UUID id) {
        f.call('relationApi.removeRelation', id)
        if (f.relations.remove(id) == null) { throw new FakeNotFound("Relation with id '${id}' not found") }
    }

    FPage findRelations(Dto req) {
        f.call('relationApi.findRelations', req)
        def p = req.props
        FakeCollibra.onlyProps('findRelations', p, ['relationTypeId', 'sourceId', 'targetId', 'limit', 'cursor'])
        def hits = f.relations.values().findAll { r ->
            (p.relationTypeId == null || r.typeId == p.relationTypeId) &&
                (p.sourceId == null || r.sourceId == p.sourceId) &&
                (p.targetId == null || r.targetId == p.targetId)
        }.sort { it.seq }
        return f.page(hits.collect { f.relationView(it) }, p)
    }
}

class FakeAttributeApi {
    FakeCollibra f

    FPage findAttributes(Dto req) {
        f.call('attributeApi.findAttributes', req)
        def p = req.props
        FakeCollibra.onlyProps('findAttributes', p, ['typeIds', 'assetId', 'limit', 'cursor'])
        def hits = f.attributes.values().findAll { at ->
            (p.typeIds == null || (p.typeIds as Collection).contains(at.typeId)) &&
                (p.assetId == null || at.assetId == p.assetId)
        }.sort { it.seq }
        return f.page(hits.collect { f.attributeView(it) }, p)
    }

    FAttribute addAttribute(Dto req) {
        f.call('attributeApi.addAttribute', req)
        def p = req.props
        FakeCollibra.requireProps('AddAttributeRequest', p, ['assetId', 'typeId', 'value'])
        f.requireAsset((UUID) p.assetId)
        if (!f.attributeTypes.containsKey(p.typeId)) { throw new FakeNotFound("Attribute type '${p.typeId}' not found") }
        def at = [id: f.newId(), assetId: p.assetId, typeId: p.typeId, value: p.value, seq: ++f.seq]
        f.attributes[at.id] = at
        return f.attributeView(at)
    }
}

class FakeWorkflowDefinitionApi {
    FakeCollibra f

    FDefinition getWorkflowDefinitionByProcessId(String processId) {
        f.call('workflowDefinitionApi.getWorkflowDefinitionByProcessId', processId)
        def d = f.workflowDefinitions[processId]
        if (d == null) { throw new FakeNotFound("Workflow definition with process id '${processId}' not found") }
        return new FDefinition(id: d.id, processId: processId, name: d.name)
    }
}

class FakeWorkflowInstanceApi {
    FakeCollibra f

    List<Map> startWorkflowInstances(Dto req) {
        f.call('workflowInstanceApi.startWorkflowInstances', req)
        def p = req.props
        def def_ = f.workflowDefinitions.values().find { it.id == p.workflowDefinitionId }
        if (def_ == null) { throw new FakeNotFound("Workflow definition '${p.workflowDefinitionId}' not found") }
        def itemIds = ((p.addBusinessItemId ?: []) + (p.businessItemIds ?: [])) as List
        def started = [id: f.newId(), definitionId: p.workflowDefinitionId, processId: def_.processId,
                       businessItemIds: itemIds, businessItemType: p.businessItemType?.toString(),
                       formProperties: new LinkedHashMap((Map) (p.formProperties ?: [:])),
                       sendNotification: p.sendNotification]
        f.startedInstances << started
        return [started]
    }
}

class FakeAttachmentApi {
    FakeCollibra f

    Map addAttachment(Dto req) {
        f.call('attachmentApi.addAttachment', req)
        def p = req.props
        def stream = p.fileStream
        byte[] bytes = stream instanceof InputStream ? ((InputStream) stream).readAllBytes() : (stream as byte[])
        f.requireAsset((UUID) p.baseResourceId)
        def at = [id: f.newId(), baseResourceId: p.baseResourceId, discriminator: p.baseResourceDiscriminator?.toString(),
                  fileName: p.fileName, bytes: bytes]
        f.attachments << at
        return at
    }
}
