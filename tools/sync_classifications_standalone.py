#!/usr/bin/env python3
"""
Standalone equivalent of the "Sync Data X-Ray Classifications" workflow.

Mirrors shared/classification_sync.groovy over the Collibra REST API so that the
assets it writes are indistinguishable from the workflow's — the search, import,
rerun and nightly workflows keep working against them:

  - reads GET /api/v1/classifications from Data X-Ray (Bearer token)
  - maps LABEL / ANNOTATOR / EXTRACTOR / CLASSIFICATION to the pack's asset types;
    ANNOTATOR_DOMAIN (data categories) is skipped, exactly like the workflow
  - UPSERTS by the "Data X-Ray ID" attribute (the public UUID); adopts a same-named
    untagged asset once; disambiguates colliding names with the subtype
  - renames when the Data X-Ray name changed; reactivates a retired asset that came back
  - tags every asset "dataxray-classification-sync"
  - sets Data X-Ray ID, Description, Link, Search Link, Sub Type (links prefixed with
    the Data X-Ray base URL); optionally the numeric "Data X-Ray Index ID" the Edge
    edition of search/rerun needs (--stamp-index-ids, uses Data X-Ray's internal
    /api/tags, /api/data-classes and /api/metadata-extractors)
  - RETIRES (status Obsolete, never deletes) tagged assets no longer in the catalogue;
    skipped entirely when the catalogue comes back empty

=============================================================================
SECTION 1 — WHAT THE TECHNICIAN EDITS (connection settings)
=============================================================================
Nothing below this docstring needs editing for a normal deployment. Settings
are supplied as environment variables (or a `.env` file next to the script):

  COLLIBRA_URL        https://<tenant>.collibra.com
  DXR_BASE_URL        https://<data-x-ray-host>   (also the prefix of the Link
                      / Search Link attributes unless --link-base is given)
  DXR_API_KEY         Data X-Ray Bearer token (same kind of token the on-prem
                      workflow edition uses)

  Collibra authentication — pick ONE of the three (COLLIBRA_AUTH selects it
  explicitly: basic | jwt | oauth2; otherwise the first one whose variables
  are set wins, in this order):

  basic   COLLIBRA_USER + COLLIBRA_PASSWORD
          a Collibra user with a LOCAL password, allowed to create/edit/tag
          assets in the "Data X-Ray Classifications" domain
  jwt     COLLIBRA_JWT
          a JSON Web Token issued by the identity provider the Collibra admin
          registered under Settings > Security > JWT; sent as
          "Authorization: Bearer". For SSO-only tenants when the token is
          obtained by other means (a vault, a CI secret, a manual login).
  oauth2  COLLIBRA_OAUTH_TOKEN_URL + COLLIBRA_OAUTH_CLIENT_ID + COLLIBRA_OAUTH_CLIENT_SECRET
          [+ COLLIBRA_OAUTH_SCOPE, COLLIBRA_OAUTH_AUDIENCE]
          OAuth2 client-credentials grant against the identity provider's token
          endpoint; the script fetches the JWT itself and refreshes it when it
          expires. The recommended mode for SSO-only tenants: the client is a
          service principal whose token carries the username claim Collibra is
          configured to map to a Collibra user, and THAT user holds the
          permissions.

  Whatever the mode, the script first calls GET /rest/2.0/users/current and
  prints the Collibra user it resolved to, so a wrongly mapped token is
  caught before anything is written.

Command-line switches: --dry-run, --stamp-index-ids, --link-base URL, and
--collibra-url / --collibra-user / --dxr-url to override the variables above.

=============================================================================
SECTION 2 — WHAT MUST NOT BE EDITED (fixed model ids)
=============================================================================
The UUIDs in the "canonical ids" block below are the same on EVERY Collibra
instance: configure-data-xray-workflows creates the domain, asset types,
attribute types and roles with exactly these ids, and the search / rerun /
import / nightly workflows and the form pickers hard-code them too. Each
constant names the matching constant (or list entry) in
workflows/configure-data-xray-workflows/scripts/configure_data_xray.groovy.
If an instance has different ids, the fix is to run the configure workflow,
not to edit this script.

Usage:
  python sync_classifications_standalone.py --dry-run
  python sync_classifications_standalone.py [--stamp-index-ids] [--link-base https://dxr.example.com]

Exit code 0 when every item synced, 1 when any item failed or the run was aborted.
Requires: requests (python-dotenv optional).
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter, defaultdict

import requests

try:  # optional
    from dotenv import load_dotenv
    load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))
    load_dotenv()
except ImportError:  # pragma: no cover
    pass

# =============================================================================
# CANONICAL IDS — DO NOT EDIT. Must match configure_data_xray.groovy exactly.
# (right-hand comment = the name in configure_data_xray.groovy)
# =============================================================================
CLASSIFICATIONS_DOMAIN_ID = "019c9fbf-622c-76f4-9dd6-2a9730a11515"  # domainDefs: 'Data X-Ray Classifications'
ASSET_TYPE_BY_DXR_TYPE = {                                            # assetTypeDefs (Data X-Ray type -> Collibra asset type)
    "CLASSIFICATION": "01965d43-235d-796b-be49-078f91d7472a",        #   'Classification'
    "ANNOTATOR":      "01922a69-e7a0-7ac7-a581-c9ba9286ccf1",        #   'Annotator'
    "EXTRACTOR":      "019c9fbd-a91b-7242-9451-79ab632163a3",        #   'Extractor'
    "LABEL":          "019c9fbe-25c3-71b7-90ac-057dd582fa1e",        #   'Label'
}
ATTR_DXR_ID       = "019e73ae-1aa8-700c-8086-626326822c22"  # DXID_ATTR_ID        'Data X-Ray ID' — the upsert key every other workflow reads
ATTR_DXR_INDEX_ID = "019e9211-4a7c-7b1e-9d3f-2c8e5f6a0b41"  # INDEX_ID_ATTR_ID    'Data X-Ray Index ID' (Cloud + Edge edition)
ATTR_DESCRIPTION  = "00000000-0000-0000-0000-000000003114"  # DESCRIPTION_ATTR_ID Collibra system 'Description'
ATTR_LINK         = "019c9fc5-aa4c-72af-8918-caa54fe61eba"  # LINK_ATTR_ID        'Link'
ATTR_SEARCH_LINK  = "019c9fc5-8ff5-77a7-962d-4b6b05c69254"  # SEARCH_LINK_ATTR_ID 'Search Link'
ATTR_SUBTYPE      = "019c9fc5-ecc8-759b-9c0b-78547fa315ad"  # SUBTYPE_ATTR_ID     'Sub Type'
STATUS_OBSOLETE   = "00000000-0000-0000-0000-000000005011"  # Collibra built-in status, same on every instance
STATUS_CANDIDATE  = "00000000-0000-0000-0000-000000005008"  # Collibra built-in status, same on every instance
SYNC_TAG          = "dataxray-classification-sync"          # dxrClassificationSyncTag() in shared/dxr_model.groovy
# =============================================================================

PAGE = 1000


# ---------------------------------------------------------------- clients
class BearerAuth(requests.auth.AuthBase):
    """Authorization: Bearer <jwt>. `token_source()` returns a fresh token when the current one is about to expire."""

    def __init__(self, token_source):
        self._token_source = token_source

    def __call__(self, r):
        r.headers["Authorization"] = f"Bearer {self._token_source()}"
        return r


class OAuth2ClientCredentials:
    """Fetches a JWT from the identity provider's token endpoint (client_credentials) and caches it until ~60 s before expiry."""

    def __init__(self, token_url: str, client_id: str, client_secret: str, scope: str | None, audience: str | None):
        self.token_url, self.client_id, self.client_secret = token_url, client_id, client_secret
        self.scope, self.audience = scope, audience
        self._token, self._expires_at = None, 0.0

    def __call__(self) -> str:
        import time
        if self._token and time.time() < self._expires_at - 60:
            return self._token
        form = {"grant_type": "client_credentials", "client_id": self.client_id, "client_secret": self.client_secret}
        if self.scope:
            form["scope"] = self.scope
        if self.audience:
            form["audience"] = self.audience
        r = requests.post(self.token_url, data=form, headers={"Accept": "application/json"}, timeout=60)
        if not r.ok:
            raise RuntimeError(f"OAuth2 token request to {self.token_url} failed: HTTP {r.status_code} {r.text[:300]}")
        body = r.json()
        if "access_token" not in body:
            raise RuntimeError(f"OAuth2 token response has no access_token: {str(body)[:300]}")
        self._token = body["access_token"]
        self._expires_at = time.time() + float(body.get("expires_in", 300))
        return self._token


def build_collibra_auth(env: dict) -> tuple[str, object]:
    """Pick the Collibra authentication from the environment. Returns (mode, requests auth object)."""
    mode = (env.get("COLLIBRA_AUTH") or "").strip().lower()
    if not mode:
        if env.get("COLLIBRA_USER") and env.get("COLLIBRA_PASSWORD"):
            mode = "basic"
        elif env.get("COLLIBRA_JWT"):
            mode = "jwt"
        elif env.get("COLLIBRA_OAUTH_TOKEN_URL"):
            mode = "oauth2"
        else:
            raise RuntimeError("No Collibra credentials: set COLLIBRA_USER/COLLIBRA_PASSWORD, or COLLIBRA_JWT, or COLLIBRA_OAUTH_TOKEN_URL/CLIENT_ID/CLIENT_SECRET")
    if mode == "basic":
        missing = [k for k in ("COLLIBRA_USER", "COLLIBRA_PASSWORD") if not env.get(k)]
        if missing:
            raise RuntimeError(f"COLLIBRA_AUTH=basic needs {', '.join(missing)}")
        return mode, requests.auth.HTTPBasicAuth(env["COLLIBRA_USER"], env["COLLIBRA_PASSWORD"])
    if mode == "jwt":
        if not env.get("COLLIBRA_JWT"):
            raise RuntimeError("COLLIBRA_AUTH=jwt needs COLLIBRA_JWT")
        token = env["COLLIBRA_JWT"].strip()
        return mode, BearerAuth(lambda: token)
    if mode == "oauth2":
        missing = [k for k in ("COLLIBRA_OAUTH_TOKEN_URL", "COLLIBRA_OAUTH_CLIENT_ID", "COLLIBRA_OAUTH_CLIENT_SECRET") if not env.get(k)]
        if missing:
            raise RuntimeError(f"COLLIBRA_AUTH=oauth2 needs {', '.join(missing)}")
        source = OAuth2ClientCredentials(env["COLLIBRA_OAUTH_TOKEN_URL"], env["COLLIBRA_OAUTH_CLIENT_ID"], env["COLLIBRA_OAUTH_CLIENT_SECRET"],
                                         env.get("COLLIBRA_OAUTH_SCOPE"), env.get("COLLIBRA_OAUTH_AUDIENCE"))
        return mode, BearerAuth(source)
    raise RuntimeError(f"Unknown COLLIBRA_AUTH '{mode}' (expected basic, jwt or oauth2)")


class Collibra:
    def __init__(self, base_url: str, auth, dry_run: bool):
        self.base = base_url.rstrip("/") + "/rest/2.0"
        self.s = requests.Session()
        self.s.auth = auth
        self.s.headers["Accept"] = "application/json"
        self.dry_run = dry_run

    def whoami(self) -> str:
        """The Collibra user the credentials resolve to — proves a JWT is mapped to the intended account before writing."""
        r = self.s.get(self.base + "/users/current")
        if r.status_code == 401:
            raise RuntimeError(f"Collibra rejected the credentials: {r.text[:200]}")
        j = self._check(r, "GET /users/current")
        return j.get("userName") or j.get("id") or "?"

    def _check(self, r: requests.Response, what: str) -> dict | list | None:
        if not r.ok:
            raise RuntimeError(f"{what}: HTTP {r.status_code} {r.text[:300]}")
        return r.json() if r.text else None

    def page_all(self, path: str, **params) -> list:
        out, offset = [], 0
        while True:
            j = self._check(self.s.get(self.base + path, params={**params, "offset": offset, "limit": PAGE}), f"GET {path}")
            out.extend(j["results"])
            if len(j["results"]) < PAGE:
                return out
            offset += PAGE

    # -- writes (no-ops in dry-run) --
    def add_asset(self, name: str, domain_id: str, type_id: str) -> str:
        if self.dry_run:
            return f"dry-run-{name}"
        j = self._check(self.s.post(self.base + "/assets", json={"name": name, "displayName": name, "domainId": domain_id, "typeId": type_id}), "POST /assets")
        return j["id"]

    def change_asset(self, asset_id: str, **fields) -> None:
        if not self.dry_run:
            self._check(self.s.patch(f"{self.base}/assets/{asset_id}", json=fields), f"PATCH /assets/{asset_id}")

    def add_tag(self, asset_id: str, tag: str) -> None:
        if not self.dry_run:
            self._check(self.s.post(f"{self.base}/assets/{asset_id}/tags", json={"tagNames": [tag]}), f"POST /assets/{asset_id}/tags")

    def set_attribute(self, asset_id: str, type_id: str, value) -> None:
        """Replace all values of one attribute type (empty value clears it) — same as assetApi.setAssetAttributes."""
        values = [] if value is None or (isinstance(value, str) and not value.strip()) else [value]
        if not self.dry_run:
            self._check(self.s.put(f"{self.base}/assets/{asset_id}/attributes", json={"typeId": type_id, "values": values}), f"PUT /assets/{asset_id}/attributes")


def fetch_catalogue(dxr_base: str, token: str) -> list[dict]:
    r = requests.get(dxr_base.rstrip("/") + "/api/v1/classifications", headers={"Authorization": f"Bearer {token}"}, timeout=120)
    if not r.ok:
        raise RuntimeError(f"Data X-Ray /api/v1/classifications: HTTP {r.status_code} {r.text[:300]}")
    data = r.json().get("data")
    if not isinstance(data, list):
        raise RuntimeError("Data X-Ray response has no 'data' list")
    return data


def fetch_index_ids(dxr_base: str, token: str) -> dict[str, str]:
    """Public UUID -> numeric search-index id, from Data X-Ray's internal catalogue endpoints."""
    h = {"Authorization": f"Bearer {token}"}
    out: dict[str, str] = {}
    base = dxr_base.rstrip("/")
    for path in ("/api/tags", "/api/metadata-extractors"):
        r = requests.get(base + path, headers=h, timeout=120); r.raise_for_status()
        body = r.json()
        items = body if isinstance(body, list) else body.get("content", [])
        for it in items:
            if it.get("uuid") and it.get("id") is not None:
                out[str(it["uuid"])] = str(it["id"])
    r = requests.get(base + "/api/data-classes", headers=h, timeout=120); r.raise_for_status()
    for group in r.json().values():
        for it in group or []:
            if it.get("uuid") and it.get("id") is not None:
                out[str(it["uuid"])] = str(it["id"])
    return out


# ---------------------------------------------------------------- naming (mirrors the Groovy)
def humanize(s: str | None) -> str:
    if not s or not s.strip():
        return ""
    return " ".join(w[:1].upper() + w[1:].lower() for w in re.split(r"[_\s]+", s.strip()) if w)


def type_label(t: str | None) -> str:
    return (t[:1].upper() + t[1:].lower()) if t else ""


def disambiguate(items: list[dict]) -> dict[str, str]:
    """Data X-Ray id -> Collibra name, for names used by more than one item."""
    by_name: dict[str, list[dict]] = defaultdict(list)
    for it in items:
        if it.get("name") and it.get("id"):
            by_name[str(it["name"]).strip()].append(it)
    result: dict[str, str] = {}
    for base_name, group in by_name.items():
        if len(group) <= 1:
            continue
        labels = [humanize(it.get("subtype")) or type_label(it.get("type")) for it in group]
        counts = Counter(labels)
        for it, label in zip(group, labels):
            if counts[label] > 1:
                frag = str(it["id"]).replace("-", "")[:8]
                label = f"{label} {frag}" if label else frag
            result[str(it["id"]).strip()] = f"{base_name} ({label})"
    return result


# ---------------------------------------------------------------- sync
def sync(collibra: Collibra, items: list[dict], link_base: str, index_ids: dict[str, str] | None) -> dict:
    log = print
    counts = Counter()
    failures: list[str] = []

    if not items:
        log("Data X-Ray returned 0 classifications — nothing synced and NO retirement (guard against a broken response)")
        return {"counts": counts, "failures": ["empty catalogue"], "aborted": True}

    # Existing state of the domain
    existing = {a["id"]: a for a in collibra.page_all("/assets", domainId=CLASSIFICATIONS_DOMAIN_ID)}
    existing_by_name = {a["name"]: a for a in existing.values()}
    asset_by_dxr_id: dict[str, str] = {}
    for attr in collibra.page_all("/attributes", typeIds=ATTR_DXR_ID):
        aid = attr["asset"]["id"]
        if aid in existing and attr.get("value"):
            v = str(attr["value"]).strip()
            if v in asset_by_dxr_id and asset_by_dxr_id[v] != aid:
                log(f"WARN: Data X-Ray ID {v} is on two assets ({asset_by_dxr_id[v]}, {aid}); keeping the first")
                continue
            asset_by_dxr_id[v] = aid
    log(f"Domain has {len(existing)} asset(s), {len(asset_by_dxr_id)} with a Data X-Ray ID")

    names = disambiguate(items)
    if names:
        log(f"Disambiguated {len(names)} colliding name(s): {', '.join(sorted(names.values()))}")

    touched: set[str] = set()
    for idx, it in enumerate(items):
        name = (it.get("name") or "").strip()
        dxr_id = (str(it.get("id")) if it.get("id") is not None else "").strip()
        dxr_type = str(it.get("type") or "")
        if not name or not dxr_id:
            counts["skipped"] += 1
            log(f"Skipping item {idx}: missing name or id")
            continue
        type_id = ASSET_TYPE_BY_DXR_TYPE.get(dxr_type)
        if not type_id:
            counts["skipped"] += 1
            log(f"Skipping '{name}': unknown type '{dxr_type}'")
            continue
        asset_name = names.get(dxr_id, name)
        try:
            asset_id, is_update, previous_name, was_retired = None, False, None, False
            if dxr_id in asset_by_dxr_id:
                asset_id = asset_by_dxr_id[dxr_id]
                is_update, previous_name = True, existing[asset_id]["name"]
                was_retired = existing[asset_id]["status"]["id"] == STATUS_OBSOLETE
            elif asset_name in existing_by_name and existing_by_name[asset_name]["id"] not in touched:
                # adopt a same-named asset without a Data X-Ray ID (older name-based sync), once
                a = existing_by_name[asset_name]
                asset_id, is_update, previous_name = a["id"], True, a["name"]
                was_retired = a["status"]["id"] == STATUS_OBSOLETE
            if asset_id is None:
                asset_id = collibra.add_asset(asset_name, CLASSIFICATIONS_DOMAIN_ID, type_id)

            change = {}
            if is_update and previous_name is not None and previous_name != asset_name:
                change.update(name=asset_name, displayName=asset_name)
            if was_retired:
                change["statusId"] = STATUS_CANDIDATE
            if change:
                collibra.change_asset(asset_id, **change)
                log(f"  {'renamed' if 'name' in change else ''}{' reactivated' if was_retired else ''} '{previous_name}' -> '{asset_name}'")

            collibra.add_tag(asset_id, SYNC_TAG)
            collibra.set_attribute(asset_id, ATTR_DXR_ID, dxr_id)
            if index_ids is not None:
                num = index_ids.get(dxr_id)
                if num:
                    collibra.set_attribute(asset_id, ATTR_DXR_INDEX_ID, num)
                else:
                    log(f"  WARN: no numeric index id for '{name}' ({dxr_id})")
            collibra.set_attribute(asset_id, ATTR_DESCRIPTION, it.get("description"))
            collibra.set_attribute(asset_id, ATTR_LINK, link_base + it["link"] if it.get("link") else None)
            collibra.set_attribute(asset_id, ATTR_SEARCH_LINK, link_base + it["searchLink"] if it.get("searchLink") else None)
            collibra.set_attribute(asset_id, ATTR_SUBTYPE, it.get("subtype"))

            touched.add(asset_id)
            counts["updated" if is_update else "created"] += 1
            log(f"{'Updated' if is_update else 'Created'} {dxr_type} '{asset_name}' [{asset_id}]")
        except Exception as exc:  # one item never aborts the run
            counts["failed"] += 1
            failures.append(f"{name}: {exc}")
            log(f"FAILED '{name}': {exc}")

    # Retire tagged assets that vanished from Data X-Ray (never delete)
    tagged = collibra.page_all("/assets", domainId=CLASSIFICATIONS_DOMAIN_ID, tagNames=SYNC_TAG)
    for a in tagged:
        if a["id"] in touched or a["status"]["id"] == STATUS_OBSOLETE:
            continue
        try:
            collibra.change_asset(a["id"], statusId=STATUS_OBSOLETE)
            counts["retired"] += 1
            log(f"Retired '{a['name']}' [{a['id']}] — no longer in Data X-Ray")
        except Exception as exc:
            counts["failed"] += 1
            failures.append(f"retire {a['name']}: {exc}")

    return {"counts": counts, "failures": failures, "aborted": False}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--collibra-url", default=os.environ.get("COLLIBRA_URL"))
    ap.add_argument("--collibra-user", default=os.environ.get("COLLIBRA_USER"), help="Basic-auth user (see the docstring for jwt / oauth2 modes)")
    ap.add_argument("--dxr-url", default=os.environ.get("DXR_BASE_URL"), help="Data X-Ray base URL (also the prefix for links unless --link-base is given)")
    ap.add_argument("--link-base", help="Base URL to prefix Link / Search Link with (default: --dxr-url)")
    ap.add_argument("--stamp-index-ids", action="store_true", help="Also write the numeric Data X-Ray Index ID (needed by the Cloud + Edge edition of search/rerun)")
    ap.add_argument("--dry-run", action="store_true", help="Read everything, write nothing")
    args = ap.parse_args()

    token = os.environ.get("DXR_API_KEY")
    missing = [n for n, v in {"COLLIBRA_URL": args.collibra_url, "DXR_BASE_URL": args.dxr_url, "DXR_API_KEY": token}.items() if not v]
    if missing:
        print(f"Missing configuration: {', '.join(missing)}", file=sys.stderr)
        return 2
    env = dict(os.environ)
    if args.collibra_user:
        env["COLLIBRA_USER"] = args.collibra_user
    try:
        auth_mode, auth = build_collibra_auth(env)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    dxr_url = args.dxr_url if args.dxr_url.startswith("http") else "https://" + args.dxr_url
    link_base = (args.link_base or dxr_url).rstrip("/")

    items = fetch_catalogue(dxr_url, token)
    print(f"Data X-Ray catalogue: {len(items)} item(s) {dict(Counter(i.get('type') for i in items))}{' [DRY RUN]' if args.dry_run else ''}")
    index_ids = fetch_index_ids(dxr_url, token) if args.stamp_index_ids else None

    collibra = Collibra(args.collibra_url, auth, args.dry_run)
    try:
        print(f"Collibra: authenticated with {auth_mode} as '{collibra.whoami()}'")
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    result = sync(collibra, items, link_base, index_ids)
    c = result["counts"]
    print(f"\nSync complete: created={c['created']} updated={c['updated']} retired={c['retired']} skipped={c['skipped']} failed={c['failed']}")
    for f in result["failures"]:
        print(f"  - {f}")
    return 1 if result["aborted"] or c["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
