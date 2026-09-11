# collibra-dxr-workflows

The **Data X-Ray ↔ Collibra integration**: six Collibra workflows (Groovy + BPMN + JSON forms) released together as one bundle. This is a *workflow pack* built and deployed with the [collibra-workflower](https://github.com/Ohalo-Ltd/collibra-workflower) harness — see that repo's CLAUDE.md for packaging format, config-variable rules, redeploy semantics, REST testing tips and the Groovy runtime facts. This file holds only what is specific to the Data X-Ray workflows.

## Layout

```
pack.json                  Pack manifest: bundle name, pinned harnessRef, workflow list, extra bundle files
workflows/<name>/          One directory per workflow (app.json, workflow.bpmn, scripts/, forms/)
docs/deployment-guide.md   Customer-facing UI deployment guide (shipped in the bundle)
docs/file-asset-lifecycle.md  Customer-facing explainer: file asset creation, 25k cap, sync, orphaning/retirement, deletion (shipped)
docs/images/               Screenshots referenced by the docs (bundle.extra ships the docs dir, so relative links survive)
docs/acceptance-criteria.md  Lifecycle acceptance criteria / UAT checklist (shipped in the bundle)
workflow-registry.json     Workflow name → Collibra definition UUID, per environment (dev instances)
.github/workflows/release-bundle.yml  Builds the release bundle with the pinned harness on `release: published`
```

Developing: work from a checkout of the harness with this repo mounted at `packs/dxr-workflows` (`git clone --recurse-submodules`). From the harness root: `python deploy.py search-data-xray --dry-run`, `python deploy.py bundle --pack packs/dxr-workflows`. Commit and push pack changes *inside* `packs/dxr-workflows`, then bump the submodule pointer in the harness.

Releasing: `gh release create vX.Y.Z --generate-notes` in this repo. CI checks out the harness at `pack.json`'s `harnessRef` and attaches `collibra-data-xray-workflows-vX.Y.Z.zip`. If a harness change alters the ZIP output, bump `harnessRef` first.

## Access control for the Data X-Ray workflows

Two custom **global roles** gate the Data X-Ray workflows. Both are created (idempotently, with fixed canonical UUIDs) by `configure-data-xray-workflows`:

| Role | UUID | Purpose |
|---|---|---|
| `Data X-Ray Admin` | `019e9000-abcd-7000-a115-74a17d050000` | Granted the `WORKFLOW_ADMINISTRATION` global permission so holders (alongside Sysadmins) can edit each workflow's Base URL + Bearer token. ⚠️ That permission is **global** — it confers admin over *all* workflows on the instance; Collibra has no per-workflow admin permission. |
| `Data X-Ray User` | `019e9000-abce-7000-9e7d-a7a7115e0000` | Bare membership role, no permissions. Only gates who may *run* the search/sync workflows. |

**No delete-enforcement role**: a "Classification Manager" resource role (mirroring Admin membership onto the Classifications domain via responsibilities) was built, verified and then **deliberately rolled back** (Aug 2026) — Collibra's default already denies asset deletion to anyone without an explicitly granted delete-capable responsibility, so the extra role added moving parts without adding real protection. The guidance is documentation-level: don't grant Steward/Owner-style responsibilities on the Classifications/Files domains. (Learned along the way, kept for reference: `responsibilityApi` IS injected into workflow scripts even though `stubs/CollibraStubs.groovy` doesn't list it; resource permissions on a *global* role would apply instance-wide, so scoped grants require a resource role + responsibility.)

**Who can run** = the workflow definition's start roles, set *only* at runtime by `configure-data-xray-workflows` (Phase 7) via `workflowDefinitionApi.changeWorkflowDefinition(...)` with `startRoleIds`. This is a **partial PATCH** — only `startRoleIds` is sent, so configuration variables (the admin-set token/URL) are **not** wiped. search/sync/rerun → User + Admin; the two nightlies + configure → Admin.

⚠️ **The BPMN's `flowable:candidateStarterGroups` is inert in Collibra** — verified by test: a fresh deploy lands with *no* start-role restriction (`startRoles` empty) regardless of what the attribute says, and configure's BPMN says `flowableUser` yet its enforced `startRoles` is `[Data X-Ray Admin]`. Leave it at the canonical `flowableUser` (matches real Workflow Designer exports); do **not** try to encode access there. The read model exposes start roles as `startRoles` (array of role objects); the *request* field is `startRoleIds` (UUIDs) — don't confuse the two when inspecting via REST.

**Start roles persist exactly like configuration variables.** An in-place redeploy/re-import (POST upsert, same definition UUID) **preserves** `startRoles` — verified. They reset to unrestricted only if a workflow is **deleted** and reimported (same rule as config vars). So after a delete+reimport, **re-run configure** to re-assert access.

**Configuration variables are not per-role.** `readable="false"` already hides the Base URL + token from *everyone* who starts a workflow (any role); only Sysadmin / `WORKFLOW_ADMINISTRATION` holders can edit them on the settings page. Granting `Data X-Ray Admin` that permission is the only lever — nothing "hides" the token from Users because it already is hidden.

**Bootstrap (2 steps).** Import/deploy all six workflows, then run `configure-data-xray-workflows` once (as a Collibra admin / Sysadmin) — it creates the roles and stamps `startRoles` on every deployed target in a single pass. configure skips run-access for any workflow not yet deployed, so if you run it before everything is imported, **re-run it** once the rest are in.

## File import model (gated import + rerun + nightly sync)

Search results can be imported as **Data X-Ray File** assets in the **Data X-Ray Files** domain (canonical UUIDs in `configure_data_xray.groovy`, hardcoded as constants in the scripts). Key mechanics:

- **Upsert key**: deterministic asset UUID = `UUID.nameUUIDFromBytes("dxr-file:" + dxrFileId)`. No lookup index needed — `assetApi.exists(id)` is the upsert check, and any query's import/rerun converges on the same asset per file.
- **Names**: full name = filename + ` [8-hex-of-UUID]` suffix (per-domain name uniqueness vs duplicate filenames at 10k scale); clean filename in `displayName` (the File type has `displayNameEnabled`).
- **Per-query membership**: dedicated **returns/returned by** relation type (query → file). Do NOT use `groups` (7017) for this — 7017 already means query→classification and file→classification.
- **Retire, never delete**: rerun unlinks files no longer returned, then retires (status Obsolete `…005011`) only files **no** query returns any more; reactivates (Candidate `…005008`) on reappearance. The classification sync uses the same retire semantics for orphaned classifications. A run that returns **0 results skips retirement** (transient-outage guard).
- **Batch loop**: collector script (sync) streams the DXR NDJSON into a gzip+Base64 JSON work-list process variable; an **async** script task (`flowable:async="true"`) processes 50 items per execution/transaction, looped by an exclusive gateway on `${hasMoreWork}` — Collibra's prescribed bulk-ops shape. The blob is written once; only an integer cursor advances.
- **Instance-wide cap**: max **25,000** assets total in the Files domain (projected = current population + net-new), warning above 10,000. Enforced in the scripts (forms are bypassable), surfaced via form visibility bindings.
- **Keep-in-sync**: tag `dataxray-keep-in-sync` on the query asset; `sync-data-xray-files-nightly` (02:30, after the 02:00 classification sync) starts one **headless** `rerunDataXraySearch` per flagged query via `startWorkflowInstances(... formProperties([headless: 'true']))`. Headless runs never throw and skip the summary user task via a gateway on `${headless == 'true'}`. Flagged queries whose status is **Obsolete are skipped** — archiving a query pauses its nightly sync (manual rerun still allowed); reactivating resumes it. The driver then runs an **orphan sweep**: any Files-domain asset with no "returns" relation from any query is retired — this is what cleans up after a *deleted* query asset (Collibra deletes its relations with it, and per-query retire passes can never see it). The sweep aborts on any scan failure rather than retiring from a partial picture. Timer-start workflows can also be started manually via `POST /workflowInstances` (verified) — handy for testing.
- **Relation reconciliation**: the batch stage diffs each pre-existing file's `groups` links against the row's current hit evidence — stale links (label deleted/unassigned in DXR) are REMOVED, not just skipped. A result row carries the file's complete classification state, so this is query-independent.
- **Rerun exclusivity/scoping**: `rerunDataXraySearch` runs with exclusivity `UNCONSTRAINED` (default RESOURCE_EXCLUSIVITY hides the start action while a summary task is open and would block the nightly fan-out). It cannot be scoped to the query asset type — asset-type assignment rules reject workflows with **global** start roles (`workflowWrongRoles`) — so `rerun_collector.groovy` hard-guards the item's asset type at runtime.
- **Rerun criteria**: rebuilt from the query asset's 7017 relations (classifications' **current** names — DXR renames just work) + the "Annotated Text Filter" attribute, scoped to the annotators linked by the query's **searches text in / text searched by** relations (`019e9210-f2a1-…`, created by configure). Composition: every label/extractor/annotator is its own AND clause; the phrase clause is `annotators: { (name:"A" OR name:"C") AND annotations.phrase:"*text*" }` — OR only between the annotators explicitly picked for the text (agreed with Colin Walker, Aug 2026). A criterion whose asset is Obsolete (deleted in DXR) fails the rerun with a message naming it — never silently broadens the query. The "Data X-Ray Query" attribute is a human-readable record of the last run, not rerun input.
- **DXR row-shape assumptions** (field names for file id/size/modified, per-classification hit counts, deep-link path) live in `extractFileTuple`/`extractClassifications` in `import_collector.groovy` + `rerun_collector.groovy` (kept in sync, like the `import_batch.groovy`/`sync_files_batch.groovy` twins) — verify against a live `/api/v1/files` NDJSON response when DXR versions change. Hit-evidence rule: a classification listed as *checked with 0 hits* must NOT get a relation.


## Data X-Ray API facts (verified against a Data X-Ray demo instance, 2026-08)

- `/api/v1/files` NDJSON row: `fileId` (not `id`), `fileName`, `path`, `size`, `lastModifiedAt`, `datasource: {id, name}`, `labels: [{id, name}]`, `extractedMetadata: [{id, name, value, type}]`, `annotators: [{id, name, uniquePhrases, annotations: [...]}]`. **No UI link field** on file rows — don't fabricate deep links.
- Extractor results are queried via **`extractedMetadata.name:"X"`** — `extractors.name:"X"` matches nothing.
- File rows list annotators only with hit evidence fields (`uniquePhrases`/`annotations`); labels/extractedMetadata by presence.

