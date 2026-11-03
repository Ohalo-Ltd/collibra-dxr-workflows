// rerun_common.groovy — rebuilding a saved query's criteria from its asset.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// The saved criteria are NOT a frozen query string: the query is REBUILT from
//   – the query asset's "groups" relations to classification assets, using
//     their CURRENT names (so a DataXray-side rename never breaks a saved
//     search — the classification sync keeps those names up to date), and
//   – the "Annotated Text Filter" attribute stored at search time, scoped to the
//     annotators linked by the query's "searches text in" relations, and
//   – the "Extracted Value Filter" attribute (v2.12), scoped to the extractors
//     linked by its "searches values of" relations.
//
// Classification lifecycle:
//   – renamed in DataXray → handled automatically (rebuilt from current names)
//   – deleted in DataXray → its Collibra asset is Obsolete (the sync retires,
//     never deletes). A retired criterion is reported in `deadCriteria` and the
//     caller must FAIL the rerun — silently dropping an AND criterion would
//     broaden the search and import files the user never asked for.

// {{include:dxr_query.groovy}}
// {{include:collibra_lookup.groovy}}
// {{include:query_flags.groovy}}

// Rebuild the criteria of a query asset. Returns
//   [labelNames:, extractorNames:, annotatorNames:, filter:, filterAnnotatorNames:,
//    valueFilter:, valueFilterExtractorNames:/-DxrIds:/-IndexIds:,
//    labelDxrIds:, extractorDxrIds:, annotatorDxrIds:, filterAnnotatorDxrIds:,
//    deadCriteria: [String], ignoredCriteria: int, hasCriteria: boolean]
// The *DxrIds lists carry each criterion's DataXray ID attribute ('' when the
// asset has none) in the same order as the corresponding names.
def rebuildQueryCriteria(UUID queryId, Map ids) {
    def out = [labelNames: [], extractorNames: [], annotatorNames: [], filter: '', filterAnnotatorNames: [],
               labelDxrIds: [], extractorDxrIds: [], annotatorDxrIds: [], filterAnnotatorDxrIds: [],
               labelIndexIds: [], extractorIndexIds: [], annotatorIndexIds: [], filterAnnotatorIndexIds: [],
               valueFilter: '', valueFilterExtractorNames: [], valueFilterExtractorDxrIds: [], valueFilterExtractorIndexIds: [],
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

    // Extracted value (v2.12): the stored value, scoped to the extractors linked
    // by "searches values of". Same liveness rule; the relations only count
    // when a value is stored. An instance that has not run Configure for v2.12
    // has no such attribute type, so no query carries a value.
    try {
        out.valueFilter = ids.valueFilterAttrTypeId ? readSingleAttribute(queryId, ids.valueFilterAttrTypeId) : ''
    } catch (Exception noTypeEx) {
        out.valueFilter = ''
    }
    if (!out.valueFilter.isEmpty()) {
        def spec = parseValueFilter(out.valueFilter)
        if (!spec.ok) {
            out.deadCriteria << "the stored extracted value is not valid: ${spec.error}".toString()
            return out
        }
        relationIdsByFarEnd(ids.valueFilterRelTypeId, queryId, null).keySet().each { targetId ->
            if (!out.deadCriteria.isEmpty()) { return }
            def target
            try {
                target = assetApi.getAsset(targetId)
            } catch (Exception goneEx) {
                out.deadCriteria << "extracted-value extractor ${targetId} no longer exists".toString()
                return
            }
            if (target.getStatus()?.getId() == ids.obsoleteStatusId) {
                out.deadCriteria << "extracted-value extractor '${target.getName()}' was deleted in DataXray (its Collibra asset is retired)".toString()
                return
            }
            out.valueFilterExtractorNames << target.getName()
            out.valueFilterExtractorDxrIds << readSingleAttribute(targetId, ids.dataxrayIdAttrTypeId)
            out.valueFilterExtractorIndexIds << indexIdOf(targetId)
        }
    }
    if (!out.deadCriteria.isEmpty()) { return out }

    out.hasCriteria = !(out.labelNames.isEmpty() && out.extractorNames.isEmpty()
                        && out.annotatorNames.isEmpty() && out.filter.isEmpty() && out.valueFilter.isEmpty())
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

// --- The Rerun Options task (v2.10) ---------------------------------------------------
//
// Every manual rerun stops at a Rerun Options task before it changes anything
// (a user who passed on importing the first time could only
// recreate the search). It shows how many files the search matches now, and
// offers the Search Results task's three options again, prefilled from the
// sync filter's tags: create assets for the new matches on this run, keep in
// sync nightly, create assets for new files on later reruns. Headless runs
// (the nightly) never stop there and follow the tags.

// Whether this run may create the new file assets it would need. `newFiles` is
// exact on-prem (the whole answer is in hand) and a LOWER bound in the Cloud +
// Edge edition (today's matches minus the files the search returned before:
// files that left the results make room for as many new ones, which a count
// cannot see). A lower bound over a limit is a sure refusal; under it, the
// Edge results pass enforces the limits as it creates (maxCreate,
// rerunCreateBudget). Returns the Rerun Options form's [importAllowed:,
// importWarn:, importBlocked:, importBlockedMessage:, projectedTotal:].
def computeRerunFeasibility(int total, int newFiles, int filesDomainCount, boolean exact) {
    String upTo = exact ? '' : 'at least '
    def fmt = { int n -> String.format(Locale.ENGLISH, '%,d', n) }
    int projectedTotal = filesDomainCount + newFiles
    boolean tooMany = newFiles > dxrMaxImportFiles()
    boolean overCap = !tooMany && newFiles > 0 && projectedTotal > dxrMaxTotalFileAssets()
    boolean importBlocked = tooMany || overCap
    boolean importAllowed = total > 0 && !importBlocked
    boolean importWarn = importAllowed && newFiles > dxrWarnImportFiles()
    def message = tooMany
        ? "Creating assets for new files is disabled for this run: it would add ${upTo}${fmt(newFiles)} new file asset(s), more than the ${fmt(dxrMaxImportFiles())} one run may create. Send with the first option unticked to refresh only the files already in Collibra, or refine the search."
        : overCap
        ? "Creating assets for new files is disabled for this run: Collibra holds ${fmt(filesDomainCount)} DataXray file asset(s) and this run would add ${upTo}${fmt(newFiles)} more — the projected ${fmt(projectedTotal)} exceeds the ${fmt(dxrMaxTotalFileAssets())} instance-wide limit. Send with the first option unticked to refresh only the files already in Collibra."
        : ''
    return [importAllowed: importAllowed, importWarn: importWarn, importBlocked: importBlocked,
            importBlockedMessage: message.toString(), projectedTotal: projectedTotal]
}

// Publish the Rerun Options form's variables. `keepInSync` / `autoImport` are
// the sync filter's current options (its tags), which prefill the checkboxes:
// sending the task unchanged keeps today's behaviour. The two lasting options
// are always offered, however many files the search matches: they must stay
// changeable (above all, switchable off) on a search too large to import, and
// the limits only ever concern creating assets.
def publishRerunOptions(Map f, int total, int newFiles, boolean exact, boolean keepInSync, boolean autoImport) {
    def fmt = { int n -> String.format(Locale.ENGLISH, '%,d', n) }
    execution.setVariable('rerunResultCount', total)
    execution.setVariable('rerunResultCountDisplay', fmt(total))
    execution.setVariable('rerunNewFileCount', newFiles)
    execution.setVariable('rerunNewFilesExact', exact)
    execution.setVariable('rerunNewFilesDisplay', (exact ? fmt(newFiles) : "at least ${fmt(newFiles)}").toString())
    execution.setVariable('rerunPreviousCountDisplay', fmt(previousFileCount()))
    execution.setVariable('importAllowed', f.importAllowed)
    execution.setVariable('importWarn', f.importWarn)
    execution.setVariable('importBlocked', f.importBlocked)
    execution.setVariable('importBlockedMessage', f.importBlockedMessage)
    execution.setVariable('syncOffered', true)
    execution.setVariable('importDecision', autoImport && f.importAllowed)
    execution.setVariable('keepInSync', keepInSync)
    execution.setVariable('autoImportNewFiles', autoImport)
}

// The form variables of a run that never reaches the Rerun Options task (it
// was aborted first), so nothing downstream reads an unset variable.
def zeroRerunOptions() {
    publishRerunOptions([importAllowed: false, importWarn: false, importBlocked: false, importBlockedMessage: ''],
                        0, 0, true, false, false)
    execution.setVariable('rerunCreateNew', false)
}

// Apply the run's options once the Rerun Options task is sent (or, headless,
// once the collector is done): writes the sync filter's tags from the
// checkboxes, decides whether this run creates assets for new files
// (rerunCreateNew), and records the rebuilt query (rerunQueryDisplay) in the
// DataXray Query attribute — only now, so a run left at the task, refused or
// aborted before it changes nothing on the sync filter. A headless run follows
// the tags the collector read (rerunTagNoAutoImport).
//
// Interactive, with "create assets" ticked: the task may have been open for
// days, so the DataXray file asset population is counted again
// (filesDomainCount, which the limits that follow use) and the limits are
// re-checked against it. A request the limits block — whether the form showed
// it as blocked or it became so since (forms can also be bypassed through the
// API) — is refused with a WorkflowException before anything is written, so
// the task stays open.
//
// Sets rerunSyncOptionChanged when "keep in sync nightly" changed, so the
// retire task can bring the DataXray Sync value of the files the run did not
// get to up to date (refreshSyncModesAfterOptionChange). Returns rerunCreateNew.
def applyRerunOptions(UUID queryId) {
    def ids = dxrModelIds()
    boolean createNew
    if ((execution.getVariable('headless') ?: '').toString() == 'true') {
        createNew = execution.getVariable('rerunTagNoAutoImport') != true
    } else {
        boolean importNow = execution.getVariable('importDecision') == true
        if (importNow) {
            int filesDomainCount = countFileAssets(ids)
            execution.setVariable('filesDomainCount', filesDomainCount)
            def f = computeRerunFeasibility((execution.getVariable('rerunResultCount') ?: 0) as int,
                                            (execution.getVariable('rerunNewFileCount') ?: 0) as int,
                                            filesDomainCount, execution.getVariable('rerunNewFilesExact') != false)
            if (f.importBlocked || execution.getVariable('importBlocked') == true) {
                def message = (f.importBlockedMessage ?: execution.getVariable('importBlockedMessage') ?:
                               'Creating assets for new files is disabled for this run.').toString()
                def wf = new com.collibra.dgc.workflow.api.exception.WorkflowException(message)
                wf.setTitleMessage('Rerun DataXray Search refused')
                wf.setUserMessage(message)
                throw wf
            }
        }
        def tags = applyQueryFlags(queryId, execution.getVariable('keepInSync') == true,
                                   execution.getVariable('autoImportNewFiles') != false)
        execution.setVariable('rerunSyncOptionChanged', tags.keepInSync != (execution.getVariable('rerunTagKeepInSync') == true))
        createNew = importNow
        loggerApi.info("Rerun options for sync filter ${queryId}: create assets for new files now=${importNow}, keep in sync=${tags.keepInSync}, no auto-import=${tags.noAutoImport}")
    }
    def queryDisplay = (execution.getVariable('rerunQueryDisplay') ?: '').toString()
    if (queryDisplay) { recordQueryAttribute(queryId, ids, queryDisplay) }
    execution.setVariable('rerunCreateNew', createNew)
    return createNew
}

// How many file assets the rest of this run may still create (the Cloud + Edge
// results pass, processFileBatch's maxCreate): the per-run limit and the room
// under the instance-wide one (filesDomainCount, counted when the options were
// applied, or by the collector of a headless run), less what it created so far.
def rerunCreateBudget() {
    int filesDomainCount = (execution.getVariable('filesDomainCount') ?: 0) as int
    int created = (execution.getVariable('importCreatedCount') ?: 0) as int
    return Math.max(0, Math.min(dxrMaxImportFiles(), dxrMaxTotalFileAssets() - filesDomainCount) - created)
}

// After the Rerun Options task changed "keep in sync nightly", every file the
// search still returns must change its DataXray Sync value. The results pass
// rewrites it for each file it upserts and the retire pass for each file it
// unlinks; a run that does not get through its results (0 results, a partial
// or aborted pass, a pass the retire guards skip) would leave the rest stale
// until the nightly sweep. The retire scripts call this on those paths: it
// recomputes the value of every file the search returned before the run. It
// writes nothing but that attribute, so the zero-results and partial-run
// guards on links and statuses are untouched.
def refreshSyncModesAfterOptionChange() {
    if (execution.getVariable('rerunSyncOptionChanged') != true) { return }
    def previous = new groovy.json.JsonSlurper().parseText((execution.getVariable('previousFileIds') ?: '[]').toString()) as List
    if (previous.isEmpty()) { return }
    int written = refreshFileSyncModes(previous, dxrModelIds())
    loggerApi.info("Keep in sync nightly changed: DataXray Sync recomputed on ${written} file asset(s) this run did not otherwise update")
}
