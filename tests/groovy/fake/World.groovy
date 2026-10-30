package fake

// One test's world: a fresh FakeCollibra (with the integration's operating
// model), a FakeDataXray, the process variables and log of the shared
// functions, and the shared functions themselves, bound to all of that. A test
// body runs with the World as its delegate, so it calls shared functions
// (retireOrphanFiles(…), dxrModelIds()) and the helpers below directly.
class World {
    final TestKit kit
    final FakeCollibra fx = new FakeCollibra()
    final FakeDataXray dxr = new FakeDataXray()
    final FakeLogger logger = new FakeLogger()
    final FakeExecution execution = new FakeExecution()
    final Script shared
    final Map ids

    static final UUID WORKFLOW_USER = FakeCollibra.WORKFLOW_USER
    static final UUID PERSON = FakeCollibra.PERSON
    static final UUID CANDIDATE = FakeCollibra.CANDIDATE
    static final UUID OBSOLETE = FakeCollibra.OBSOLETE
    static final UUID ACCEPTED = FakeCollibra.ACCEPTED
    static final UUID UNDER_REVIEW = FakeCollibra.UNDER_REVIEW

    World(TestKit kit) {
        this.kit = kit
        shared = (Script) kit.sharedClass.getDeclaredConstructor().newInstance()
        shared.binding = bindingFor(execution, null)
        ids = (Map) shared.invokeMethod('dxrModelIds', [] as Object[])
        fx.seedModel(ids)
        kit.workflows.each { name, runner ->
            fx.workflowDefinitions[runner.processId] = [id: UUID.nameUUIDFromBytes("fake-definition:${runner.processId}".toString().bytes),
                                                        name: name, processId: runner.processId]
        }
    }

    /** Shared functions are called as if they were the World's own methods. */
    def methodMissing(String name, Object args) {
        return shared.invokeMethod(name, args)
    }

    Binding bindingFor(FakeExecution exe, UUID itemId) {
        def b = new Binding()
        b.setVariable('execution', exe)
        b.setVariable('loggerApi', logger)
        ['assetApi', 'relationApi', 'attributeApi', 'attributeTypeApi', 'statusApi', 'relationTypeApi', 'assetTypeApi',
         'workflowDefinitionApi', 'workflowInstanceApi', 'attachmentApi'].each { b.setVariable(it, fx."${it}") }
        b.setVariable('item', itemId == null ? null : new FakeItem(id: itemId))
        b.setVariable('string2Uuid', { s -> UUID.fromString(s.toString()) })
        return b
    }

    String getVariant() { kit.variant }

    Map getVars() { execution.vars }

    // --- seeding ---------------------------------------------------------------------
    // Groovy hands named arguments over as a leading Map, so every helper that
    // takes options has a Map-first form: fileAsset('f1', status: X).

    UUID query(Map opts, String name) { query(name, opts) }
    UUID classification(Map opts, String name, String kind = 'label') { classification(name, kind, opts) }
    UUID fileAsset(Map opts, String fileId) { fileAsset(fileId, opts) }
    UUID link(Map opts, UUID source, String kind, UUID target) { link(source, kind, target, opts) }
    List tuple(Map o, String fileId) { tuple(fileId, o) }
    RunResult run(Map opts, String name) { run(name, opts) }
    Map dxrFile(Map o, String fileId) { dxrFile(fileId, o) }


    /** A sync filter (query) asset. opts: status, tags, labels/annotators/extractors (classification ids), filter, filterAnnotators. */
    UUID query(String name, Map opts = [:]) {
        def a = fx.insertAsset(name: name, domainId: ids.queryDomainId, typeId: ids.queryAssetTypeId,
                               statusId: opts.status ?: CANDIDATE)
        a.tags.addAll(opts.tags ?: [])
        ((opts.labels ?: []) + (opts.annotators ?: []) + (opts.extractors ?: [])).each { link(a.id, 'groups', it) }
        if (opts.filter) {
            fx.setAttributeValues(a.id, ids.filterAttrTypeId, [opts.filter])
            (opts.filterAnnotators ?: []).each { link(a.id, 'textFilter', it) }
        }
        return a.id
    }

    /** A classification asset. kind: label, annotator, extractor. opts: dxrId, indexId, status, domainId, tags. */
    UUID classification(String name, String kind = 'label', Map opts = [:]) {
        def a = fx.insertAsset(name: name, domainId: opts.domainId ?: ids.classificationsDomainId,
                               typeId: ids["${kind}TypeId".toString()], statusId: opts.status ?: CANDIDATE)
        a.tags.addAll(opts.tags ?: [])
        fx.setAttributeValues(a.id, ids.dataxrayIdAttrTypeId, [(opts.dxrId ?: UUID.nameUUIDFromBytes("dxr:${name}".toString().bytes)).toString()])
        if (opts.indexId != null) { fx.setAttributeValues(a.id, ids.dataxrayIndexIdAttrTypeId, [opts.indexId.toString()]) }
        return a.id
    }

    /**
     * A DataXray File asset as a sync would have left it. opts: status, ds, obj
     * (object key attributes), name, domainId, id (else derived from fileId).
     */
    UUID fileAsset(String fileId, Map opts = [:]) {
        UUID id = (UUID) (opts.id ?: shared.invokeMethod('deterministicFileAssetId', [fileId] as Object[]))
        String fileName = opts.name ?: "${fileId}.pdf"
        def a = fx.insertAsset(id: id, name: shared.invokeMethod('uniqueAssetName', [fileName, id] as Object[]),
                               displayName: fileName, domainId: opts.domainId ?: ids.filesDomainId,
                               typeId: ids.fileTypeId, statusId: opts.status ?: CANDIDATE)
        fx.setAttributeValues(id, ids.dataxrayIdAttrTypeId, [fileId])
        if (opts.ds != null) { fx.setAttributeValues(id, ids.datasourceIdAttrTypeId, [opts.ds.toString()]) }
        if (opts.obj != null) { fx.setAttributeValues(id, ids.objectIdAttrTypeId, [opts.obj.toString()]) }
        return a.id
    }

    /** A domain outside the integration's community (for moved-asset tests). */
    UUID otherDomain(String name = 'Finance Documents') {
        UUID id = UUID.nameUUIDFromBytes("fake-domain:${name}".toString().bytes)
        fx.domains[id] = name
        return id
    }

    UUID relationType(String kind) {
        def key = kind == 'textFilter' ? 'textFilterRelTypeId' : "${kind}RelationTypeId".toString()
        def id = ids[key]
        if (id == null) { throw new IllegalArgumentException("unknown relation kind '${kind}'") }
        return (UUID) id
    }

    /** A relation. opts.by: 'person' records it as made by a person. */
    UUID link(UUID source, String kind, UUID target, Map opts = [:]) {
        def make = { fx.insertRelation(source, target, relationType(kind)).id }
        return (UUID) (opts.by == 'person' ? fx.asPerson(make) : make())
    }

    /** A work-item tuple (dxr_rows.groovy layout); classes = classification asset ids. */
    List tuple(String fileId, Map o = [:]) {
        [fileId, o.dsName ?: 'SharePoint', o.path ?: "/docs/${fileId}.pdf", o.name ?: "${fileId}.pdf",
         (o.size ?: '1024').toString(), o.modified ?: '2025-04-10T17:22:00Z', o.link ?: '',
         (o.classes ?: []).collect { it.toString() }, (o.ds ?: '').toString(), (o.obj ?: '').toString(), o.values ?: []]
    }

    /**
     * A file in the fake DataXray (see FakeDataXray for the keys). Defaults:
     * datasource 312 'SharePoint', object id 'obj-<fileId>', name '<fileId>.pdf'.
     * Returns the live map: change labels/annotators on it to change DataXray.
     */
    Map dxrFile(String fileId, Map o = [:]) {
        def f = [id: fileId, ds: 312, dsName: 'SharePoint', objectId: "obj-${fileId}".toString(), name: "${fileId}.pdf".toString(),
                 folder: '/Shared Documents', size: 27885, modified: '2025-04-10T17:22:00Z',
                 labels: [], annotators: [:], extractors: [:], phrases: [:]] + o
        dxr.add(f)
        return f
    }

    /** The UUID string a DataXray file's asset gets. */
    UUID assetIdOf(String fileId) { (UUID) shared.invokeMethod('deterministicFileAssetId', [fileId] as Object[]) }

    // --- running workflows -------------------------------------------------------------

    BpmnRunner workflow(String name) {
        def r = kit.workflows[name]
        if (r == null) { throw new IllegalArgumentException("workflow '${name}' not built for variant ${kit.variant} (have ${kit.workflows.keySet()})") }
        return r
    }

    /** Start a workflow. opts: item (UUID), vars (Map), maxSteps. */
    RunResult run(String name, Map opts = [:]) {
        workflow(name).start([fake: fx, dataxray: dxr, item: opts.item, vars: opts.vars ?: [:],
                              initiator: opts.initiator, maxSteps: opts.maxSteps, bindingFor: this.&bindingFor])
    }

    RunResult complete(RunResult run, Map formValues = [:]) { run.runner.complete(run, formValues) }

    /** Run every instance started through workflowInstanceApi that has not run yet. */
    List<RunResult> runStarted() {
        def out = []
        fx.startedInstances.findAll { !it.ran }.each { inst ->
            inst.ran = true
            def name = kit.workflows.find { k, v -> v.processId == inst.processId }?.key
            out << run(name, [item: inst.businessItemIds ? inst.businessItemIds[0] : null, vars: inst.formProperties])
        }
        return out
    }

    // --- reading back ----------------------------------------------------------------------

    String status(UUID assetId) { fx.statuses[fx.statusOf(assetId)] }

    boolean linked(UUID source, String kind, UUID target) {
        !fx.relationsOf(relationType(kind), [source: source, target: target]).isEmpty()
    }

    List<Map> links(String kind, Map where = [:]) { fx.relationsOf(relationType(kind), where) }

    Object attr(UUID assetId, String key) {
        def values = fx.attributeValues(assetId, (UUID) ids["${key}AttrTypeId".toString()])
        return values ? values[0] : null
    }

    List<UUID> fileAssets() { fx.assets.values().findAll { it.typeId == ids.fileTypeId }.sort { it.seq }.collect { it.id } }

    boolean logged(String level, String fragment) { logger.has(level, fragment) }
}

class FakeItem {
    UUID id
    String type = 'ASSET'
}
