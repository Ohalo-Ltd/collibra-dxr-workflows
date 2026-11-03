// migrate_classified_by.groovy  (both editions)
//
// ASYNC first task of Sync DataXray Files (Nightly), BEFORE the driver (v2.11).
// Moves the integration's file → classification links from Collibra's
// built-in "groups" type to the pack's "classified by / classifies" type
// (the wording "groups / is grouped by" was confusing).
// Reruns already move the links of every file they touch (reconcileFileGroups);
// this catches the rest — files no search reruns, one-time imports — so an
// instance converges within a night or two of running Configure.
//
// Rules:
//   – only links whose SOURCE is a DataXray File asset (sync filters keep
//     linking their criteria with "groups", and other content on the instance
//     may use "groups" too: never touched);
//   – only links the integration made (createdBy = Collibra's Workflow user).
//     A person's "groups" link stays exactly as it is. This task is ASYNC so
//     that the links it adds are recorded as the Workflow user's too —
//     otherwise every later sync would treat them as a person's;
//   – it runs before the driver starts tonight's headless reruns, which
//     reconcile the same links against DataXray (reconcileFileGroups): moving
//     a link while a rerun swaps it to "formerly" could recreate a stale
//     "classified by" link. A manual rerun can still run meanwhile, so each
//     link is re-read just before it is moved (skipped if it changed), and
//     the old link is removed only once its replacement exists. A link a
//     concurrent rerun still turns stale is swapped by the file's next sync;
//   – at most dxrMigrateLinksMaxPerNight() links per night; the rest follow
//     the next night (logged);
//   – nothing happens until Configure has created the new type.
// Never throws: a timer-started workflow must end cleanly.
//
// Writes (audit): nightlyLinksMigrated (Integer), nightlyLinksMigrateLeft (Integer)

import com.collibra.dgc.core.api.dto.instance.relation.FindRelationsRequest

// {{include:dxr_model.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:file_batch.groovy}}

def ids = dxrModelIds()
int migrated = 0
int left = 0
int skippedChanged = 0
try {
    if (!classifiedByOn()) {
        loggerApi.info("Sync DataXray Files (Nightly): 'classified by' relation type not created yet (run Configure DataXray Workflows) — file links stay 'groups'")
    } else {
        def fileIds = [] as Set
        eachFileAsset(ids) { fileIds << it.getId() }
        def owned = integrationIdentityIds()
        def already = [] as Set   // "<file>:<classification>" pairs linked with the new type
        def candidates = []       // integration-made "groups" links from a file asset
        [[ids.classifiedByRelationTypeId, { rel -> already << "${rel.getSource().getId()}:${rel.getTarget().getId()}".toString() }],
         [ids.groupsRelationTypeId, { rel ->
             def src = rel.getSource().getId()
             if (fileIds.contains(src) && owned.contains(rel.getCreatedBy())) { candidates << rel }
         }]].each { pair ->
            def cursor = ''
            while (true) {
                def page = relationApi.findRelations(FindRelationsRequest.builder()
                    .relationTypeId(pair[0]).limit(1000).cursor(cursor).build())
                page.getResults().each { pair[1](it) }
                cursor = page.getNextCursor()
                if (!cursor) { break }
            }
        }
        int cap = dxrMigrateLinksMaxPerNight()
        left = Math.max(0, candidates.size() - cap)
        candidates.take(cap).each { rel ->
            def src = rel.getSource().getId()
            def tgt = rel.getTarget().getId()
            // Still there as scanned? (one source + target lookup: just this link) Otherwise something reconciled the file since.
            if (relationsByFarEnd(ids.groupsRelationTypeId, src, tgt)[tgt]?.id != rel.getId()) { skippedChanged++; return }
            boolean linked = already.contains("${src}:${tgt}".toString()) ||
                addRelationQuietly(com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest.builder()
                    .sourceId(src).targetId(tgt).typeId(ids.classifiedByRelationTypeId).build())
            if (linked && removeRelationQuietly(rel.getId())) { migrated++ }
        }
        loggerApi.info("Sync DataXray Files (Nightly): moved ${migrated} file link(s) from 'groups' to 'classified by'${left ? ", ${left} left for the next night" : ''}${skippedChanged ? ", ${skippedChanged} changed since the scan (left to the file's next sync)" : ''}")
    }
} catch (Exception migrateEx) {
    loggerApi.error("Sync DataXray Files (Nightly): moving file links to 'classified by' stopped: ${migrateEx.message}")
}
execution.setVariable('nightlyLinksMigrated', migrated)
execution.setVariable('nightlyLinksMigrateLeft', left)
