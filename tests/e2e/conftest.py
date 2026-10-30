"""Fixtures for the live end-to-end tests against a shared Collibra dev instance.

Opt-in: DXR_E2E=1. They run against the workflows already deployed there (the
`default` entries of workflow-registry.json) and the instance's real DataXray,
so they follow collision rules:

- Everything they create is named "[e2e] … <run id>" and is deleted at the end,
  even when a test fails. DXR_E2E_SWEEP=1 first deletes "[e2e]" leftovers of an
  aborted earlier run.
- Lifecycle scenarios use stand-in file assets (fake DataXray ids and object
  keys) linked to throwaway sync filters. DataXray is only read, never changed.
- One throwaway search imports the files of a small label (DXR_E2E_LABEL,
  default 'collibra-workflows-e2e': a label kept for this suite, on 3 files of
  datasource 312 on demo.dataxray.io). File assets it creates are deleted afterwards;
  ones that already existed only gain, and then lose, a link to it.
- Nothing that touches every search runs unless asked: DXR_E2E_NIGHTLY=1 runs
  the files nightly (it reruns every keep-in-sync search and sweeps every file),
  DXR_E2E_CONFIGURE=1 runs Configure twice (story 32).
- The `returns` / `formerly returns` links of every sync filter that existed
  before the run are compared at the end and must be unchanged.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

import pytest

from collibra import Collibra

PACK = Path(__file__).resolve().parents[2]
E2E = "[e2e]"


def pytest_collection_modifyitems(items):
    for item in items:
        if "/e2e/" in str(item.fspath):
            item.add_marker(pytest.mark.e2e)


@pytest.fixture(scope="session")
def dev():
    if os.environ.get("DXR_E2E") != "1":
        pytest.skip("live tests: set DXR_E2E=1 (they run against the shared Collibra dev instance)")
    import groovy_suite
    c = Collibra(groovy_suite.harness_root())
    registry = json.loads((PACK / "workflow-registry.json").read_text(encoding="utf-8"))
    env = os.environ.get("DXR_E2E_ENV", "default")
    c.definitions = {wf: ids[env] for wf, ids in registry.items() if env in ids}
    description = c.get(f"/workflowDefinitions/{c.definitions['rerun-data-xray-search']}").get("description", "")
    c.edition = "edge" if "(cloud-edge)" in description else "onprem"
    c.run_id = time.strftime("%Y%m%d-%H%M%S")
    if os.environ.get("DXR_E2E_SWEEP") == "1":
        sweep_leftovers(c)
    return c


def sweep_leftovers(c: Collibra):
    ids = c.ids
    left = [a["id"] for d in ("filesDomainId", "queryDomainId", "classificationsDomainId")
            for a in c.page_all("/assets", domainId=ids[d], name=E2E, nameMatchMode="START")]
    c.delete_assets(left)
    for com in c.page_all("/communities", name=E2E, nameMatchMode="START"):
        c.call("DELETE", f"/communities/{com['id']}")
    print(f"\nswept {len(left)} leftover {E2E} asset(s)")


@pytest.fixture(scope="session")
def edge(dev):
    if dev.edition != "edge":
        pytest.skip("Cloud + Edge scenario; the instance runs the on-prem edition")
    return dev


class Tracker:
    """Everything a run creates or changes, undone at the end."""

    def __init__(self, c: Collibra):
        self.c = c
        self.assets: list[str] = []
        self.communities: list[str] = []
        self.instances: list[str] = []
        self.restore: list[tuple[str, dict]] = []    # (asset id, fields to PATCH back)

    def name(self, what: str) -> str:
        return f"{E2E} {what} {self.c.run_id}"

    def asset(self, what, domain_key, type_key, status="Candidate") -> str:
        aid = self.c.add_asset(self.name(what), self.c.ids[domain_key], self.c.ids[type_key], status)
        self.assets.append(aid)
        return aid

    def standin(self, what, ds=None, obj=None, status="Candidate") -> str:
        """A DataXray File asset a sync could have made; ds/obj give it an object key."""
        aid = self.asset(what, "filesDomainId", "fileTypeId", status)
        self.c.set_attr(aid, "dataxrayIdAttrTypeId", f"e2e-{self.c.run_id}-{what}")
        if ds is not None:
            self.c.set_attr(aid, "datasourceIdAttrTypeId", str(ds))
            self.c.set_attr(aid, "objectIdAttrTypeId", obj)
        return aid

    def started(self, instance_id: str) -> str:
        self.instances.append(instance_id)
        return instance_id

    def undo(self):
        errors = []
        for t in self.c.open_tasks_of(self.instances):          # close the tasks our runs left open
            try:
                self.c.complete(t["id"])
            except Exception as ex:                             # noqa: BLE001
                errors.append(f"task {t['id']}: {ex}")
        for aid, fields in reversed(self.restore):
            try:
                self.c.change_asset(aid, **fields)
            except Exception as ex:                             # noqa: BLE001
                errors.append(f"restore {aid}: {ex}")
        try:
            self.c.delete_assets(self.assets)
        except Exception as ex:                                 # noqa: BLE001
            errors.append(f"delete assets: {ex}")
        for com in self.communities:
            try:
                self.c.call("DELETE", f"/communities/{com}")
            except Exception as ex:                             # noqa: BLE001
                errors.append(f"delete community {com}: {ex}")
        return errors


def returns_snapshot(c: Collibra, query_ids):
    return {q: (sorted(r["target"]["id"] for r in c.links("returns", source=q)),
                sorted(r["target"]["id"] for r in c.links("formerlyReturns", source=q))) for q in query_ids}


@pytest.fixture(scope="session")
def track(dev):
    existing = [a["id"] for a in dev.page_all("/assets", typeIds=dev.ids["queryAssetTypeId"])]
    before = returns_snapshot(dev, existing)
    t = Tracker(dev)
    yield t
    errors = t.undo()
    after = returns_snapshot(dev, [q for q in existing if q in before])
    changed = [q for q in before if before[q] != after.get(q)]
    assert not errors, "clean-up failed (run again with DXR_E2E_SWEEP=1):\n" + "\n".join(errors)
    assert not changed, f"sync filters that existed before the run changed: {changed}"


@pytest.fixture(scope="session")
def lifecycle_search(edge, track):
    """A throwaway sync filter made by Search DataXray (Import now) over a small label's files."""
    c = edge
    label_name = os.environ.get("DXR_E2E_LABEL", "collibra-workflows-e2e")
    labels = [a for a in c.page_all("/assets", typeIds=c.ids["labelTypeId"], name=label_name, nameMatchMode="EXACT")
              if a["status"]["name"] != "Obsolete"]
    if not labels:
        pytest.skip(f"no active label named {label_name!r} on this instance (set DXR_E2E_LABEL)")
    before_files = {a["id"] for a in c.page_all("/assets", typeIds=c.ids["fileTypeId"])}
    name = track.name("lifecycle search")
    inst = track.started(c.start(c.definitions["search-data-xray"], conditionName=name,
                                 description="Temporary: DataXray workflows e2e test, deleted when the run ends",
                                 label=labels[0]["id"]))
    results = c.wait_task(inst, "resultsTask")
    query = next(a["id"] for a in c.page_all("/assets", domainId=c.ids["queryDomainId"], name=name, nameMatchMode="EXACT"))
    track.assets.append(query)
    c.complete(results["id"], importDecision=True, keepInSync=False, autoImportNewFiles=True)
    summary = c.wait_task(inst, "importSummaryTask")
    c.complete(summary["id"])
    files = [r["target"]["id"] for r in c.links("returns", source=query)]
    track.assets += [f for f in files if f not in before_files]
    if not 1 <= len(files) <= 50:
        pytest.skip(f"label {label_name!r} matches {len(files)} file(s); pick one with 1-50 (DXR_E2E_LABEL)")
    return {"query": query, "name": name, "label": labels[0], "files": files,
            "results_title": results["title"], "summary_title": summary["title"]}

