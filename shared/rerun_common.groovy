// rerun_common.groovy — rebuilding a saved query's criteria from its asset.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// The saved criteria are NOT a frozen query string: the query is REBUILT from
//   – the query asset's "groups" relations to classification assets, using
//     their CURRENT names (so a DataXray-side rename never breaks a saved
//     search — the classification sync keeps those names up to date), and
//   – the "Annotated Text Filter" attribute stored at search time, scoped to the
//     annotators linked by the query's "searches text in" relations.
//
// Classification lifecycle:
//   – renamed in DataXray → handled automatically (rebuilt from current names)
//   – deleted in DataXray → its Collibra asset is Obsolete (the sync retires,
//     never deletes). A retired criterion is reported in `deadCriteria` and the
//     caller must FAIL the rerun — silently dropping an AND criterion would
//     broaden the search and import files the user never asked for.

// {{include:collibra_lookup.groovy}}

// Rebuild the criteria of a query asset. Returns
//   [labelNames:, extractorNames:, annotatorNames:, filter:, filterAnnotatorNames:,
//    labelDxrIds:, extractorDxrIds:, annotatorDxrIds:, filterAnnotatorDxrIds:,
//    deadCriteria: [String], ignoredCriteria: int, hasCriteria: boolean]
// The *DxrIds lists carry each criterion's DataXray ID attribute ('' when the
// asset has none) in the same order as the corresponding names.
def rebuildQueryCriteria(UUID queryId, Map ids) {
    def out = [labelNames: [], extractorNames: [], annotatorNames: [], filter: '', filterAnnotatorNames: [],
               labelDxrIds: [], extractorDxrIds: [], annotatorDxrIds: [], filterAnnotatorDxrIds: [],
               labelIndexIds: [], extractorIndexIds: [], annotatorIndexIds: [], filterAnnotatorIndexIds: [],
               deadCriteria: [], ignoredCriteria: 0, hasCriteria: false]
    // DataXray Index ID is only stamped by the Edge-edition sync; '' on on-prem installs.
    def indexIdOf = { UUID assetId -> ids.dataxrayIndexIdAttrTypeId ? readSingleAttribute(assetId, ids.dataxrayIndexIdAttrTypeId) : '' }

    relationIdsByFarEnd(ids.groupsRelationTypeId, queryId, null).keySet().each { targetId ->
        def target
        try {
            target = assetApi.getAsset(targetId)
        } catch (Exception goneEx) {
            out.deadCriteria << "criterion asset ${targetId} no longer exists".toString()
            return
        }
        if (target.getStatus()?.getId() == ids.obsoleteStatusId) {
            out.deadCriteria << "'${target.getName()}' was deleted in DataXray (its Collibra asset is retired)".toString()
            return
        }
        def typeId = target.getType()?.getId()
        def dxrId = readSingleAttribute(targetId, ids.dataxrayIdAttrTypeId)
        if (typeId == ids.labelTypeId) {
            out.labelNames << target.getName(); out.labelDxrIds << dxrId; out.labelIndexIds << indexIdOf(targetId)
        } else if (typeId == ids.extractorTypeId) {
            out.extractorNames << target.getName(); out.extractorDxrIds << dxrId; out.extractorIndexIds << indexIdOf(targetId)
        } else if (typeId == ids.annotatorTypeId) {
            out.annotatorNames << target.getName(); out.annotatorDxrIds << dxrId; out.annotatorIndexIds << indexIdOf(targetId)
        } else {
            out.ignoredCriteria++
        }
    }
    if (!out.deadCriteria.isEmpty()) { return out }

    out.filter = readSingleAttribute(queryId, ids.filterAttrTypeId)

    // Annotators the annotated-text filter is scoped to ("searches text in" relations).
    // Same liveness rule as the criteria: a retired/missing one aborts the rerun.
    // Only consulted when a phrase is actually stored — without one the relations
    // are inert (the search never wrote them, and the phrase clause isn't emitted),
    // so a stale relation must not be able to block a rerun.
    if (!out.filter.isEmpty()) {
        relationIdsByFarEnd(ids.textFilterRelTypeId, queryId, null).keySet().each { targetId ->
            if (!out.deadCriteria.isEmpty()) { return }
            def target
            try {
                target = assetApi.getAsset(targetId)
            } catch (Exception goneEx) {
                out.deadCriteria << "annotated-text annotator ${targetId} no longer exists".toString()
                return
            }
            if (target.getStatus()?.getId() == ids.obsoleteStatusId) {
                out.deadCriteria << "annotated-text annotator '${target.getName()}' was deleted in DataXray (its Collibra asset is retired)".toString()
                return
            }
            out.filterAnnotatorNames << target.getName()
            out.filterAnnotatorDxrIds << readSingleAttribute(targetId, ids.dataxrayIdAttrTypeId)
            out.filterAnnotatorIndexIds << indexIdOf(targetId)
        }
    }
    if (!out.deadCriteria.isEmpty()) { return out }

    out.hasCriteria = !(out.labelNames.isEmpty() && out.extractorNames.isEmpty()
                        && out.annotatorNames.isEmpty() && out.filter.isEmpty())
    return out
}

// Record what actually ran on the query asset (replace-all, so the attribute
// tracks the latest run).
def recordQueryAttribute(UUID queryId, Map ids, String queryDisplay) {
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(queryId)
            .typeId(ids.queryAttrTypeId)
            .values([queryDisplay] as List<Object>)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("Could not update the DataXray Query attribute on ${queryId}: ${attrEx.message}")
    }
}

// The file-asset UUID strings this query currently "returns" (for the retire diff).
def collectPreviousFileIds(UUID queryId, Map ids) {
    def previous = [] as Set
    relationIdsByFarEnd(ids.returnsRelationTypeId, queryId, null).keySet().each { previous << it.toString() }
    return previous
}

// --- Retire pass skipped: what the summary form says -------------------------------

// The rerun summary shows rerunRetireSkipReason (when rerunRetireSkipped) so a
// "No longer returned: 0" from a skipped pass is not read as "nothing changed".
def noteRetireSkipped(String reason) {
    execution.setVariable('rerunRetireSkipped', true)
    execution.setVariable('rerunRetireSkipReason', reason)
}

// Files the query returned before this run (previousFileIds, set by the collector).
def previousFileCount() {
    return (new groovy.json.JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List).size()
}

// The rerun summary's rows about the DataXray existence check (Cloud + Edge
// rerun only; shared/rerun_exists_edge.groovy fills them in): hidden and zero
// until then, so the one form serves both editions.
def zeroRerunExistsSummary() {
    execution.setVariable('rerunExistsRan', false)
    execution.setVariable('rerunExistsNoted', false)
    execution.setVariable('rerunExistsNote', '')
    ['existsCandidates', 'existsNoKey', 'existsNotInDataXray', 'existsRefreshed'].each { execution.setVariable(it, 0) }
}

def zeroResultsRetireSkipReason(int linked) {
    if (linked == 0) {
        return 'DataXray returned 0 results for this search.'
    }
    return ("DataXray returned 0 results for this search, so its ${linked} file(s) were left linked to it and unchanged. " +
        'An empty answer is far more often a DataXray or connection problem than a search that really matches nothing, so a rerun never acts on one. ' +
        'If DataXray really has none of these files any more, delete this sync filter: the nightly file sync then marks the files no other search returns as Not in any search.').toString()
}
