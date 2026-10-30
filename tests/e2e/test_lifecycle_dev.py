"""Live lifecycle scenarios on the shared Collibra dev instance (DXR_E2E=1).

What the offline suite cannot prove: that real Collibra, the real Edge
connection and the real DataXray behave as the fakes assume. Each scenario
names the TRC story or acceptance criterion it covers. Collision rules and
clean-up: see conftest.py.
"""
from __future__ import annotations

import io
import os
import zipfile

import pytest

from collibra import CollibraError, rerun

UNKNOWN_OBJECT = "e2e/no-such-object/{run}/{n}"


def real_key(c, file_id):
    return c.attr(file_id, "datasourceIdAttrTypeId"), c.attr(file_id, "objectIdAttrTypeId")


# --- search and import ------------------------------------------------------------------------------

def test_search_imports_with_full_object_id_and_named_tasks(edge, lifecycle_search):
    """Stories 10, 13, 13a; R1 (Full Object ID, CSV column); TRC 28 Oct task titles."""
    c, s = edge, lifecycle_search
    assert s["results_title"].startswith(f"Search Results: {s['name']} (")
    assert s["summary_title"].startswith(f"Import Summary: {s['name']} (")
    for f in s["files"]:
        ds, obj = real_key(c, f)
        assert ds and obj, f"file asset {f} has no object key"
        assert c.attr(f, "objectKeyAttrTypeId") == f"{ds}:{obj}"
        assert c.linked(f, "groups", s["label"]["id"])
    attachments = c.get("/attachments", baseResourceId=s["query"])["results"]
    assert attachments, "the sync filter has no results file"
    data = c.call("GET", f"/attachments/{attachments[0]['id']}/file")
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        csv = z.read(z.namelist()[0]).decode("utf-8").splitlines()
    assert csv[0] == "Datasource,Path,Full Object ID"
    assert len(csv) - 1 == len(s["files"])


# --- the TRC 28 Oct review: stories 28, 29, 31 (FILE-27) --------------------------------------------------

def test_rerun_settles_parked_files_before_its_summary(edge, lifecycle_search, track):
    c, s = edge, lifecycle_search
    ds, obj = real_key(c, s["files"][0])
    gone = track.standin("gone", ds=ds, obj=UNKNOWN_OBJECT.format(run=c.run_id, n=1))
    still_there = track.standin("still-there", ds=ds, obj=obj)
    person_label = track.asset("person label", "classificationsDomainId", "labelTypeId")
    c.link(still_there, "groups", person_label)                  # made by a person (the REST user)
    for f in (gone, still_there):
        c.link(s["query"], "returns", f)

    run = rerun(c, track, s["query"])

    assert run["title"].startswith(f"Rerun Summary: {s['name']} (")
    assert c.status(gone) == "Not in DataXray"                   # story 31
    assert c.linked(s["query"], "formerlyReturns", gone) and not c.linked(s["query"], "returns", gone)
    assert c.status(still_there) == "Not in any search"           # stories 28, 29
    assert c.attr(still_there, "filePathAttrTypeId"), "refreshed from DataXray before the summary"
    assert c.attr(still_there, "objectKeyAttrTypeId") == f"{ds}:{obj}"
    assert c.linked(still_there, "groups", s["label"]["id"])
    assert c.linked(still_there, "groups", person_label)          # EVID-9: a person's link is kept
    assert not c.linked(still_there, "formerlyGroups", person_label)
    for f in s["files"]:
        assert c.linked(s["query"], "returns", f) and c.status(f) != "Not in any search"


def test_file_another_search_returns_stays_active(edge, lifecycle_search, track):
    """Story 28; FILE-9, FILE-21."""
    c, s = edge, lifecycle_search
    shared = track.standin("shared", ds="312", obj=UNKNOWN_OBJECT.format(run=c.run_id, n=2))
    other = track.asset("other sync filter", "queryDomainId", "queryAssetTypeId")
    c.link(s["query"], "returns", shared)
    c.link(other, "returns", shared)

    rerun(c, track, s["query"])

    assert c.linked(s["query"], "formerlyReturns", shared)
    assert c.linked(other, "returns", shared)
    assert c.status(shared) == "Candidate"


def test_status_a_person_set_is_kept(edge, lifecycle_search, track):
    """FILE-20, R15."""
    c, s = edge, lifecycle_search
    accepted = track.standin("accepted", ds="312", obj=UNKNOWN_OBJECT.format(run=c.run_id, n=3), status="Accepted")
    c.link(s["query"], "returns", accepted)

    rerun(c, track, s["query"])

    assert c.status(accepted) == "Accepted"
    assert c.linked(s["query"], "formerlyReturns", accepted)


def test_moved_file_is_updated_in_place(edge, lifecycle_search, track):
    """Story 22; FILE-16; R3: a file asset moved to another community is updated there, never duplicated."""
    c, s = edge, lifecycle_search
    files_domain = c.get(f"/domains/{c.ids['filesDomainId']}")
    community = c.call("POST", "/communities", json={"name": track.name("moved files")})["id"]
    track.communities.append(community)
    domain = c.call("POST", "/domains", json={"name": track.name("moved files"), "communityId": community,
                                              "typeId": files_domain["type"]["id"]})["id"]
    moved = s["files"][0]
    track.restore.append((moved, {"domainId": c.ids["filesDomainId"]}))    # moved back before the community goes
    c.change_asset(moved, domainId=domain)
    count_before = len(c.page_all("/assets", typeIds=c.ids["fileTypeId"]))

    rerun(c, track, s["query"])

    assert c.asset(moved)["domain"]["id"] == domain
    assert c.linked(s["query"], "returns", moved)
    assert len(c.page_all("/assets", typeIds=c.ids["fileTypeId"])) == count_before


def test_retired_sync_filter_can_still_be_rerun_by_hand(edge, lifecycle_search, track):
    """Stories 17, 18; QRY-3."""
    c, s = edge, lifecycle_search
    track.restore.append((s["query"], {"statusId": c.status_id("Candidate")}))
    c.change_asset(s["query"], statusId=c.status_id("Obsolete"))
    try:
        rerun(c, track, s["query"])
    finally:
        c.change_asset(s["query"], statusId=c.status_id("Candidate"))
    assert all(c.linked(s["query"], "returns", f) for f in s["files"])


# --- zero results (FILE-11, FILE-28) --------------------------------------------------------------------------

def zero_results_filter(c, track, what):
    """A sync filter whose search matches nothing: an impossible phrase in one annotator."""
    annotator = next((a for a in c.page_all("/assets", typeIds=c.ids["annotatorTypeId"])
                      if a["status"]["name"] == "Candidate" and c.attr(a["id"], "dataxrayIndexIdAttrTypeId")), None)
    if annotator is None:
        pytest.skip("no annotator with a DataXray Index ID on this instance")
    q = track.asset(what, "queryDomainId", "queryAssetTypeId")
    c.set_attr(q, "filterAttrTypeId", f"zzqq-e2e-{c.run_id}-no-such-phrase")
    c.link(q, "textFilter", annotator["id"])
    return q


def test_zero_results_while_dataxray_has_the_files_changes_nothing(edge, lifecycle_search, track):
    """FILE-11: an empty answer is never acted on while DataXray still has the files."""
    c, s = edge, lifecycle_search
    q = zero_results_filter(c, track, "zero results, files exist")
    for f in s["files"]:
        c.link(q, "returns", f)
    statuses = {f: c.status(f) for f in s["files"]}

    rerun(c, track, q)

    assert all(c.linked(q, "returns", f) for f in s["files"])
    assert {f: c.status(f) for f in s["files"]} == statuses


def test_zero_results_and_every_file_gone_unlinks_them(edge, track):
    """FILE-28: the whole datasource was deleted in DataXray."""
    c = edge
    q = zero_results_filter(c, track, "zero results, files gone")
    gone = [track.standin(f"gone-{n}", ds="312", obj=UNKNOWN_OBJECT.format(run=c.run_id, n=10 + n)) for n in (1, 2)]
    for f in gone:
        c.link(q, "returns", f)

    rerun(c, track, q)

    for f in gone:
        assert c.linked(q, "formerlyReturns", f) and not c.linked(q, "returns", f)
        assert c.status(f) == "Not in DataXray"


# --- refusals ---------------------------------------------------------------------------------------------------

def test_dead_criterion_refuses_interactive_and_ends_headless_cleanly(edge, track):
    """Story 8; FILE-2, FILE-3; the 29 Oct 2026 headless-abort fix."""
    c = edge
    dead = track.asset("deleted label", "classificationsDomainId", "labelTypeId", status="Obsolete")
    q = track.asset("dead criterion", "queryDomainId", "queryAssetTypeId")
    c.link(q, "groups", dead)

    with pytest.raises(CollibraError) as refused:
        track.started(c.start(c.definitions["rerun-data-xray-search"], q))
    assert "was deleted in DataXray" in refused.value.body

    rerun(c, track, q, headless=True)                            # asserts: ended, not in error
    assert not c.links("returns", source=q)


# --- opt-in: things that touch every search ---------------------------------------------------------------------

@pytest.mark.skipif(os.environ.get("DXR_E2E_NIGHTLY") != "1", reason="reruns every keep-in-sync search: DXR_E2E_NIGHTLY=1")
def test_files_nightly_checks_parked_files(edge, track):
    """Story 31; NIGHT-8, NIGHT-10."""
    c = edge
    parked = track.standin("parked, gone", ds="312", obj=UNKNOWN_OBJECT.format(run=c.run_id, n=20), status="Not in any search")
    no_key = track.standin("parked, no key", status="Not in any search")

    inst = track.started(c.start(c.definitions["sync-data-xray-files-nightly"]))
    ended = c.wait_ended(inst, timeout=1200)

    assert not ended.get("inError"), ended.get("errorMessage")
    assert c.status(parked) == "Not in DataXray"
    assert c.status(no_key) == "Not in any search"


@pytest.mark.skipif(os.environ.get("DXR_E2E_CONFIGURE") != "1", reason="re-runs Configure on the instance: DXR_E2E_CONFIGURE=1")
def test_configure_twice_keeps_names_and_reruns_still_work(edge, lifecycle_search, track):
    """Story 32; ADM-3, ADM-5, R26: hand-made names survive Configure, and the sync keeps working."""
    c, s = edge, lifecycle_search
    names = {k: c.get(f"/domains/{c.ids[k]}")["name"] for k in ("filesDomainId", "queryDomainId", "classificationsDomainId")}
    for _ in range(2):
        inst = track.started(c.start(c.definitions["configure-data-xray-workflows"]))
        task = c.wait_task(inst, "resultsTask", timeout=900)
        c.complete(task["id"])
    assert {k: c.get(f"/domains/{c.ids[k]}")["name"] for k in names} == names
    rerun(c, track, s["query"])
    assert all(c.linked(s["query"], "returns", f) for f in s["files"])
