"""A small Collibra REST client for the live end-to-end tests.

Credentials come from the harness .env (COLLIBRA_URL / COLLIBRA_USER /
COLLIBRA_PASSWORD, or COLLIBRA_<ENV>_… with DXR_E2E_ENV=<env>). Model ids are
read from shared/dxr_model.groovy so they cannot drift from the workflows.
"""
from __future__ import annotations

import os
import re
import time
from pathlib import Path

import requests
from dotenv import load_dotenv

PACK = Path(__file__).resolve().parents[2]
SYSTEM_STATUS = {"Candidate": "00000000-0000-0000-0000-000000005008", "Obsolete": "00000000-0000-0000-0000-000000005011"}


def model_ids() -> dict[str, str]:
    text = (PACK / "shared" / "dxr_model.groovy").read_text(encoding="utf-8")
    body = text.split("def dxrModelIds()", 1)[1].split("\n}\n", 1)[0]
    return dict(re.findall(r"(\w+)\s*:\s*string2Uuid\('([0-9a-f-]{36})'\)", body))


class CollibraError(RuntimeError):
    def __init__(self, response: requests.Response):
        self.status = response.status_code
        self.body = response.text
        super().__init__(f"{response.request.method} {response.url} → {response.status_code}: {response.text[:600]}")


class Collibra:
    def __init__(self, harness_root: Path):
        load_dotenv(harness_root / ".env", override=True)
        env = os.environ.get("DXR_E2E_ENV", "default")
        prefix = "COLLIBRA_" if env == "default" else f"COLLIBRA_{env.upper()}_"
        try:
            self.base = os.environ[prefix + "URL"].rstrip("/") + "/rest/2.0"
            auth = (os.environ[prefix + "USER"], os.environ[prefix + "PASSWORD"])
        except KeyError as missing:
            raise RuntimeError(f"{missing} is not set in {harness_root / '.env'}") from None
        self.s = requests.Session()
        self.s.auth = auth
        self.ids = model_ids()

    # --- HTTP -------------------------------------------------------------------------------

    def call(self, method: str, path: str, **kw):
        r = self.s.request(method, self.base + path, timeout=300, **kw)
        if r.status_code >= 300:
            raise CollibraError(r)
        return r.json() if r.content and "json" in r.headers.get("Content-Type", "") else r.content

    def get(self, path, **params):
        return self.call("GET", path, params=params)

    def page_all(self, path, **params):
        out, offset = [], 0
        while True:
            page = self.get(path, offset=offset, limit=1000, **params)["results"]
            out += page
            if len(page) < 1000:
                return out
            offset += 1000

    # --- assets, attributes, relations ---------------------------------------------------------

    def asset(self, asset_id):
        return self.get(f"/assets/{asset_id}")

    def status(self, asset_id) -> str:
        return self.asset(asset_id)["status"]["name"]

    def status_id(self, name: str) -> str:
        if name in SYSTEM_STATUS:
            return SYSTEM_STATUS[name]
        hits = [s for s in self.get("/statuses", name=name)["results"] if s["name"] == name]
        if not hits:
            raise RuntimeError(f"no status named {name!r}")
        return hits[0]["id"]

    def add_asset(self, name, domain_id, type_id, status="Candidate") -> str:
        return self.call("POST", "/assets", json={"name": name, "displayName": name, "domainId": domain_id,
                                                  "typeId": type_id, "statusId": self.status_id(status)})["id"]

    def change_asset(self, asset_id, **fields):
        return self.call("PATCH", f"/assets/{asset_id}", json=fields)

    def delete_assets(self, asset_ids):
        if asset_ids:
            self.call("DELETE", "/assets/bulk", json=list(asset_ids))

    def attr(self, asset_id, type_key):
        r = self.get("/attributes", assetId=asset_id, typeIds=self.ids[type_key])["results"]
        return r[0]["value"] if r else None

    def set_attr(self, asset_id, type_key, value):
        self.call("POST", "/attributes", json={"assetId": asset_id, "typeId": self.ids[type_key], "value": value})

    def rel_type(self, kind: str) -> str:
        return self.ids["textFilterRelTypeId" if kind == "textFilter" else f"{kind}RelationTypeId"]

    def link(self, source, kind, target):
        return self.call("POST", "/relations", json={"sourceId": source, "targetId": target, "typeId": self.rel_type(kind)})["id"]

    def links(self, kind, source=None, target=None):
        params = {"relationTypeId": self.rel_type(kind)}
        if source:
            params["sourceId"] = source
        if target:
            params["targetId"] = target
        return self.page_all("/relations", **params)

    def linked(self, source, kind, target) -> bool:
        return bool(self.links(kind, source=source, target=target))

    # --- workflows ------------------------------------------------------------------------------

    def start(self, definition_id, item=None, **form):
        body = {"workflowDefinitionId": definition_id, "sendNotification": False, "formProperties": form}
        if item:
            body.update(businessItemIds=[item], businessItemType="ASSET")
        return self.call("POST", "/workflowInstances", json=body)[0]["id"]

    def wait_task(self, instance_id, key, timeout=420):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for t in self.get("/workflowTasks", limit=1000)["results"]:
                if t.get("workflowInstanceId") == instance_id and t.get("key") == key:
                    return t
            inst = self.instance(instance_id)
            if inst and inst.get("inError"):
                raise RuntimeError(f"instance {instance_id} is in error: {inst.get('errorMessage')}")
            time.sleep(3)
        raise TimeoutError(f"no task '{key}' for instance {instance_id} after {timeout} s")

    def complete(self, task_id, **props):
        self.call("POST", "/workflowTasks/completed", json={"taskIds": [task_id], "taskFormProperties": props})

    def instance(self, instance_id):
        hits = self.get("/workflowInstances", workflowInstanceIdPhrase=instance_id, limit=5)["results"]
        return next((i for i in hits if i["id"] == instance_id), None)

    def wait_ended(self, instance_id, timeout=600):
        """Wait until the instance has ended (Collibra drops ended instances from the list) or is in error."""
        deadline = time.time() + timeout
        seen = False
        grace = time.time() + 30          # a just-started instance may not be listed yet
        while time.time() < deadline:
            inst = self.instance(instance_id)
            seen = seen or inst is not None
            if (inst is None and (seen or time.time() > grace)) or (inst is not None and inst.get("ended")):
                return {"id": instance_id, "inError": False}
            if inst is not None and inst.get("inError"):
                return inst
            time.sleep(3)
        raise TimeoutError(f"instance {instance_id} still running after {timeout} s")

    def open_tasks_of(self, instance_ids):
        wanted = set(instance_ids)
        return [t for t in self.get("/workflowTasks", limit=1000)["results"] if t.get("workflowInstanceId") in wanted]


def rerun(c: Collibra, track, query: str, headless=False, wait_key="rerunSummaryTask"):
    """Run Rerun DataXray Search on `query` to its end (interactive: until its summary task, then close it)."""
    form = {"headless": "true"} if headless else {}
    inst = track.started(c.start(c.definitions["rerun-data-xray-search"], query, **form))
    if headless:
        ended = c.wait_ended(inst)
        assert not ended.get("inError"), f"headless rerun in error: {ended.get('errorMessage')}"
        return {"instance": inst}
    task = c.wait_task(inst, wait_key)
    c.complete(task["id"])
    return {"instance": inst, "title": task["title"]}
