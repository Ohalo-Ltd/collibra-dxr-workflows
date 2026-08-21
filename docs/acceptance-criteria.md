# Data X-Ray ↔ Collibra Integration — Acceptance Criteria

User acceptance criteria for the lifecycle of classifications, search queries and
file assets across the Data X-Ray ↔ Collibra integration. Each criterion is
written as Given / When / Then and carries a status:

- ✅ **Verified** — demonstrated live against a Collibra instance paired with a
  Data X-Ray instance (August 2026).
- 🔹 **Implemented** — behavior is coded and reviewed but has not been
  individually demonstrated.
- ⚠️ **Decision needed** — a gap or product decision, listed in
  [Open decisions](#open-decisions) at the end.

Terminology: "classification" = a Data X-Ray label, extractor or annotator.
"Retired" = Collibra status **Obsolete** (the asset and all its history are
kept; nothing the integration does ever deletes an asset). "Query" = an
**Unstructured Data Query** asset created by Search Data X-Ray.

---

## 1. Classification catalog (Data X-Ray → Collibra)

Owner: **Sync Data X-Ray Classifications** (manual) and its **Nightly** twin
(02:00 server time).

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| CAT-1 | A label/extractor/annotator exists in Data X-Ray | The classification sync runs | A matching asset exists in the **Data X-Ray Classifications** domain with the right asset type, the Data X-Ray ID / Description / Link / Search Link / Sub Type attributes, and the `dataxray-classification-sync` tag | ✅ |
| CAT-2 | A classification is **renamed** in Data X-Ray | The sync runs | The **same** Collibra asset (same UUID) is renamed; all relations from files and queries are preserved. Saved searches pick the new name up automatically on their next rerun (criteria are rebuilt from current names, never from stored text) | ✅ |
| CAT-3 | A classification is **deleted** in Data X-Ray | The sync runs | Its Collibra asset is **retired** (status Obsolete), never deleted. Relations from file assets and saved queries survive as records | ✅ |
| CAT-4 | A previously deleted classification **reappears** in Data X-Ray (same Data X-Ray ID) | The sync runs | The retired asset is **reactivated** (status Candidate) — same asset, history intact | ✅ |
| CAT-5 | Two Data X-Ray classifications share one name (e.g. two "VAT Number" annotators) | The sync runs | Both get distinct Collibra names, suffixed with their subtype (e.g. "VAT Number (Named Entity)"), falling back to type, then an id fragment | ✅ |
| CAT-6 | Data X-Ray returns **zero** classifications (outage / truncated response) | The sync runs | The retirement step is **skipped** — a transient failure must never retire the whole catalog | ✅ |
| CAT-7 | An asset in the Classifications domain was created **by hand** (no sync tag) | The sync runs | It is never touched — not updated, not retired | ✅ |
| CAT-8 | The nightly sync hits any failure (unset token, Data X-Ray down, every item failing) | The 02:00 timer fires | The run logs the reason and ends **cleanly** — it never throws, so the schedule is never disabled by Collibra's retry-then-disable rule | ✅ |
| CAT-9 | A regular Collibra user (no delete-capable responsibility on the domain) tries to delete a classification asset | They attempt the delete | Refused by Collibra's default permission model — asset deletion always requires an explicitly granted responsibility whose role includes asset removal, or Sysadmin. The integration grants no such responsibility; instances should not either (see D-2) | ✅ (Collibra default) |
| CAT-10 | A Sysadmin (or a user someone granted a delete-capable responsibility) hard-deletes a classification asset that still exists in Data X-Ray | The next sync runs | The asset is recreated (matched by Data X-Ray ID) — but relations from saved queries to the old asset are gone, silently narrowing those searches. Prefer letting the sync retire; treat the domain as sync-owned | ⚠️ documented limitation, see D-2 |

## 2. Per-file classification evidence

Owner: the import/rerun batch stage. "Evidence" = what a file's row in the
Data X-Ray `/api/v1/files` response asserts.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| EVID-1 | A **label** is applied to a file (present on its result row) | The file is imported or re-synced | The file asset has a `groups` relation to the Label asset | ✅ |
| EVID-2 | A label is **removed from a file** (or deleted) in Data X-Ray, so its row no longer lists it | The file is re-synced (rerun or nightly) | The stale `groups` relation is **removed** from the file asset | ✅ |
| EVID-3 | An **annotator** produced hits on a file (`uniquePhrases` > 0 / non-empty `annotations`) | The file is imported or re-synced | The file asset has a `groups` relation to the Annotator asset | ✅ |
| EVID-4 | An annotator was **checked but produced zero hits** on a file | The file is imported or re-synced | **No** relation is created — checked-with-zero is not a match, and an existing relation would be removed | ✅ |
| EVID-5 | An **extractor** produced metadata for a file (an `extractedMetadata` entry, any value) | The file is imported or re-synced | The file asset has a `groups` relation to the Extractor asset | 🔹 |
| EVID-6 | A search uses an extractor as a criterion | The query is composed | The Data X-Ray query uses `extractedMetadata.name:"…"` (the `extractors.name` field matches nothing) | ✅ |
| EVID-7 | A file's row references a classification **not yet synced** into Collibra | The file is imported or re-synced | The link is skipped and counted as "unresolved" in the summary; running the classification sync and re-syncing the search resolves it | 🔹 |
| EVID-8 | A label was just unassigned in Data X-Ray but its file rows **still report it** (indexing lag) | A rerun happens in that window | The relation is kept — Collibra always mirrors what the Data X-Ray API asserts. It disappears on the first sync after Data X-Ray stops reporting it. **Accepted by TRC** (see D-4) | ✅ (observed live) |

## 3. Search & gated import

Owner: **Search Data X-Ray**.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| IMP-1 | A user runs a search with any mix of labels/extractors/annotators and an optional annotated-text filter | The search completes | A query asset is created in **Data X-Ray Custom Queries** holding: `groups` relations to each criterion, the Annotated Text Filter and Data X-Ray Query attributes, a top-50 HTML preview, and the full result set attached as a zipped CSV | ✅ |
| IMP-2 | The results task is shown | The user does **not** tick "Import…" | No file assets are created; the query asset and its attachments remain | ✅ |
| IMP-3 | A search matches **0** files | The results task is shown | The import controls are not offered | 🔹 |
| IMP-4 | Current Files-domain population + this result set would exceed **25,000** | The results task is shown / import is forced via API | The import is refused with a message naming the projected total (enforced in script, not only in the form) | 🔹 |
| IMP-5 | A search matches more than **10,000** files (within cap) | The results task is shown | Import is offered with a duration warning | 🔹 |
| IMP-6 | The user ticks "Import all N…" | The import finishes | One file asset per result in **Data X-Ray Files**: clean filename as display name, uniqueness-suffixed full name, File Path / File Size / Last Modified / Datasource Name / Data X-Ray ID attributes, a `returns` relation from the query, and evidence-based classification relations. An Import Summary task reports created/updated/failed counts | ✅ |
| IMP-7 | A file was already imported by **another** query | This query's import runs | The **same** asset is reused (deterministic ID from the Data X-Ray file id) and updated in place — never duplicated | ✅ |
| IMP-8 | Data X-Ray stalls or drops the result stream mid-response | The search / import / rerun fetch runs | Up to **3 attempts** are made; on final failure nothing is saved (the transaction rolls back) and the error says so and advises retrying | ✅ |
| IMP-9 | The user ticks "Keep in sync nightly" | The import starts | The query asset is tagged `dataxray-keep-in-sync`; removing the tag by hand stops the nightly sync for that query | ✅ |
| IMP-10 | One import batch fails mid-run | The batch loop continues | The failed batch is counted and logged; remaining batches proceed — an import is never wedged by one bad batch | 🔹 |

## 4. Rerun & file asset lifecycle

Owner: **Rerun Data X-Ray Search** (from the query asset's page, or headless
via the nightly).

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| FILE-1 | A saved query's criteria were renamed in Data X-Ray since it was created | The query is rerun | The query is rebuilt from the criteria's **current** names + the stored annotated-text filter; the Data X-Ray Query attribute records what actually ran | ✅ |
| FILE-2 | A criterion's classification was deleted in Data X-Ray (its asset is retired) | An **interactive** rerun starts | The rerun fails immediately with a message **naming the criterion** — it never silently drops an AND criterion and broadens the search | ✅ |
| FILE-3 | Same as FILE-2 | The **nightly** reruns that query | The run logs the reason, touches nothing, ends cleanly; other queries are unaffected | ✅ |
| FILE-4 | A query asset has no criteria left (relations removed) and no filter | A rerun starts | It fails/skips with "criteria cannot be reconstructed" — it never falls back to an all-files search | 🔹 |
| FILE-5 | A user starts Rerun on a **non-query** asset (e.g. a file asset) | The rerun starts | It refuses immediately, naming the asset's actual type (Collibra cannot hide the action on other asset pages when start roles are global roles) | ✅ |
| FILE-6 | A **new** file matches the query since last run | The query is rerun | A new file asset is created and linked | ✅ |
| FILE-7 | A matched file was **renamed/moved** in Data X-Ray (same file id) | The query is rerun | The same asset is updated: new name/display name, refreshed attributes | 🔹 |
| FILE-8 | A file **no longer matches** the query (changed, deleted, or no longer hits the criteria) | The query is rerun | The query's `returns` relation is removed; the asset is **retired** only if no other query returns it | ✅ |
| FILE-9 | A file is returned by **two** queries and one stops matching it | That query is rerun | The file stays active (Candidate) and keeps the other query's relation | ✅ |
| FILE-10 | A **retired** file matches any query again | That query is rerun / imported | The same asset is **reactivated** (Candidate) — history intact | ✅ |
| FILE-11 | A rerun gets **0 results** from Data X-Ray | The retire pass would run | Retirement is skipped entirely — a transient empty answer must never retire a query's whole population | ✅ |
| FILE-12 | A rerun's projected Files-domain population exceeds 25,000 | The rerun starts | Interactive: refused with the projection; headless: logged and skipped | 🔹 |
| FILE-13 | A rerun's summary task is left **open** in someone's inbox | The user revisits the query asset / the nightly fires | The Rerun action is still available and the nightly still runs (workflow exclusivity is UNCONSTRAINED) | ✅ |
| FILE-14 | Data X-Ray reassigns **file ids** (e.g. datasource deleted and re-scanned) | The next sync runs | New assets are created and the old generation retires as orphans, keeping its history — file identity follows the Data X-Ray file id. **Accepted by TRC** (see D-3) | 🔹 |

## 5. Query asset lifecycle (Collibra side)

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| QRY-1 | A saved query is **deleted** in Collibra | The next nightly (02:30) runs | Its file assets are **not deleted**. Files no other query returns are **retired** by the orphan sweep; shared files are untouched. The query's attached results CSV and history are gone with it | ✅ |
| QRY-2 | A deleted query was tagged keep-in-sync | The next nightly runs | Its nightly sync simply stops (the tag died with the asset) — no errors, other queries unaffected | ✅ |
| QRY-3 | A saved query is **retired/archived** (status Obsolete) but still tagged | The next nightly runs | The query is **skipped** (logged, counted) — archiving a query is the supported way to pause its sync while keeping it restorable. Reactivating the query resumes the nightly sync; a **manual** rerun from its page still works (explicit user intent) | ✅ |
| QRY-4 | The keep-in-sync tag is **removed** from a query | The next nightly runs | The query is no longer rerun; its file assets stay exactly as they are (they are still "returned" by the query, so the sweep never touches them) | ✅ |
| QRY-5 | A query asset is **renamed** in Collibra | Anything runs | No effect — all references are by UUID. (The next rerun's summary shows the new name) | 🔹 |
| QRY-6 | A user deletes a query and wants its exclusive files **gone**, not retired | — | Supported as a manual/admin action only (bulk delete in the Files domain). The automated lifecycle never deletes | ✅ |

## 6. Nightly automation

Owner: **Sync Data X-Ray Classification (Nightly)** at 02:00 and
**Sync Data X-Ray Files (Nightly)** at 02:30.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| NIGHT-1 | Both nightlies are enabled | A night passes | Classifications sync at 02:00 **before** file reruns at 02:30, so renames/retirements are current before queries are rebuilt | 🔹 |
| NIGHT-2 | Several queries are tagged keep-in-sync and one of them fails (e.g. dead criterion) | The 02:30 nightly runs | Each query runs in its **own** headless workflow instance; one failure never blocks the others; per-query outcomes are in dgc.log | ✅ |
| NIGHT-3 | File assets exist that **no query returns** (deleted query, or a failed relation add) | The 02:30 nightly runs | The orphan sweep retires them (Obsolete). Files already Obsolete are skipped | ✅ |
| NIGHT-4 | The relation scan fails partway during the sweep | The sweep would retire files | The sweep **aborts for the night** — it never retires based on a partial picture | 🔹 |
| NIGHT-5 | The Rerun workflow isn't deployed, or config is missing | The 02:30 nightly fires | The fan-out is skipped with a logged reason; the orphan sweep still runs; the timer schedule survives | 🔹 |
| NIGHT-6 | A rerun-summary task from a manual run is open overnight | The 02:30 nightly fires | The nightly's headless rerun of that query still starts and completes (no user task in headless mode, no exclusivity conflict) | ✅ |

## 7. Deployment & configuration lifecycle

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| ADM-1 | A new version of a workflow is **re-imported** (same process ID) | The upsert completes | The admin-set Base URL/token, start roles, exclusivity and enablement are all **preserved** — a normal upgrade needs no reconfiguration | ✅ |
| ADM-2 | A workflow is **deleted and re-imported** | The import completes | Its config variables and start roles reset — re-enter the token and **re-run Configure** | ✅ (documented) |
| ADM-3 | Configure is re-run at any time | It completes | Idempotent: existing elements untouched, missing ones created, assignments augmented (unless they carry hand-tuned rules, which are reported and skipped) | ✅ |

---

## Open decisions

- ~~**D-1 (QRY-3): retired queries and the nightly.**~~ **Decided & implemented**
  (August 2026): the nightly skips queries whose status is Obsolete — archiving
  a query pauses its sync; reactivating resumes it; manual rerun stays allowed.
- ~~**D-2 (CAT-9/10): manual deletion of classification assets.**~~ **Decided**
  (August 2026): rely on Collibra's default permission model rather than a
  dedicated enforcement role. Users cannot delete assets unless explicitly
  granted a delete-capable responsibility, which the integration never does —
  so accidental deletion is already effectively impossible. Guidance (in the
  deployment guide): treat the Classifications and Files domains as sync-owned
  and don't grant Steward/Owner-style responsibilities on them. An enforcement
  role was prototyped, verified and deliberately rolled back as unnecessary
  complexity. Residual (CAT-10): a Sysadmin who hard-deletes still silently
  narrows saved queries that used the classification — prefer sync-retire.
- ~~**D-3 (FILE-14): file identity is the Data X-Ray file id.**~~ **Accepted by
  TRC** (August 2026): "old generation retired with history, new generation
  starts clean." If Data X-Ray reassigns file ids (e.g. a datasource is deleted
  and re-scanned), Collibra creates new assets and the orphan sweep retires the
  old generation — records preserved, no migration attempted. (A content-hash
  based migration remains possible later if ever needed.)
- ~~**D-4 (EVID-8): evidence freshness is bounded by Data X-Ray indexing.**~~
  **Accepted by TRC** (August 2026), with the agreed wording: "Collibra
  reflects Data X-Ray as of the last sync; changes in Data X-Ray appear after
  Data X-Ray has re-indexed and the next sync/rerun has run." Worst case with
  nightly-only syncing is about a day of lag; an on-demand rerun closes it
  immediately. The integration deliberately never second-guesses the source
  system.

**All four decisions are closed** — D-1 and D-2 decided and implemented/rolled
back as recorded above, D-3 and D-4 accepted by TRC as intended behavior.
