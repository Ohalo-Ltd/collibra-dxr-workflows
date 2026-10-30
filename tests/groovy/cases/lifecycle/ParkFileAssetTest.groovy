// parkFileAsset and the two status predicates: which file statuses the
// integration owns and may change. R15; FILE-10, FILE-20, NIGHT-7.

table('parkFileAsset', [
    [case: 'Candidate is parked', status: 'candidate', parked: true, after: 'Not in any search'],
    [case: 'Obsolete from a release before v2.4 is parked', status: 'obsolete', parked: true, after: 'Not in any search'],
    [case: 'Not in any search stays', status: 'notInAnySearch', parked: false, after: 'Not in any search'],
    [case: 'Not in DataXray stays (it says more)', status: 'notInDataXray', parked: false, after: 'Not in DataXray'],
    [case: 'Accepted (a person) stays', status: 'accepted', parked: false, after: 'Accepted'],
    [case: 'Under Review (a person) stays', status: 'underReview', parked: false, after: 'Under Review'],
]) { row ->
    def statusIds = [candidate: CANDIDATE, obsolete: OBSOLETE, accepted: ACCEPTED, underReview: UNDER_REVIEW,
                     notInAnySearch: ids.notInAnySearchStatusId, notInDataXray: ids.notInDataXrayStatusId]
    def f = fileAsset('f1', status: statusIds[row.status])

    assert parkFileAsset(fx.assetApi.getAsset(f), ids, 'test') == row.parked
    assert status(f) == row.after
    assert fx.callCount('assetApi.changeAsset') == (row.parked ? 1 : 0)
}

table('isIntegrationOwnedFileStatus / isParkedFileStatus', [
    [case: 'no status', status: null, owned: true, parked: false],
    [case: 'Candidate', status: 'candidate', owned: true, parked: false],
    [case: 'Obsolete', status: 'obsolete', owned: true, parked: true],
    [case: 'Not in any search', status: 'notInAnySearch', owned: true, parked: true],
    [case: 'Not in DataXray', status: 'notInDataXray', owned: true, parked: true],
    [case: 'Accepted', status: 'accepted', owned: false, parked: false],
]) { row ->
    def statusIds = [candidate: CANDIDATE, obsolete: OBSOLETE, accepted: ACCEPTED,
                     notInAnySearch: ids.notInAnySearchStatusId, notInDataXray: ids.notInDataXrayStatusId]
    def sid = row.status ? statusIds[row.status] : null
    assert isIntegrationOwnedFileStatus(sid, ids) == row.owned
    assert isParkedFileStatus(sid, ids) == row.parked
}
