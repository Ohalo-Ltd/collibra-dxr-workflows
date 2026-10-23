# DataXray ↔ Collibra Integration — Acceptance Criteria

User acceptance criteria for the lifecycle of classifications, search queries and
file assets across the DataXray ↔ Collibra integration. Each criterion is
written as Given / When / Then and carries a status:

- ✅ **Verified** — demonstrated live against a Collibra instance paired with a
  DataXray instance (August 2026). Rows for the v2.4 file lifecycle (the
  *Not in any search* status, *formerly* links, links and statuses set by
  people, the `dataxray-no-auto-import` tag) were verified on 22 October 2026
  in both editions (on-prem, and Cloud + Edge through an Edge HTTP
  connection). The one-off Obsolete → *Not in any search* migration (NIGHT-7)
  was verified once; it runs identically in both editions.
- 🔹 **Implemented** — behavior is coded and reviewed but has not been
  individually demonstrated.
- ⚠️ **Decision needed** — a gap or product decision, listed in
  [Open decisions](#open-decisions) at the end.

Terminology: "classification" = a DataXray label, extractor or annotator.
"Retired" = Collibra status **Obsolete**: what the classification sync sets on
a classification deleted in DataXray, and what a person sets to archive a
query. "Not in any search" = the status a **file** asset gets when no saved
search returns it any more (releases before v2.4 used Obsolete for this). In
both cases the asset and all its history are kept; nothing the integration does
ever deletes an asset. "Status set by a person" = any file-asset status other
than Candidate, Not in any search and Obsolete; the integration never changes
it. "Query" = a **DataXray Sync Filter** asset created by Search DataXray.

---

## 1. Classification catalog (DataXray → Collibra)

Owner: **Sync DataXray Classifications** (manual) and its **Nightly** twin
(02:00 server time).

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| CAT-1 | A label/extractor/annotator exists in DataXray | The classification sync runs | A matching asset exists in the **DataXray Classifications** domain with the right asset type, the DataXray ID / Description / Link / Search Link / Sub Type attributes, and the `dataxray-classification-sync` tag | ✅ |
| CAT-2 | A classification is **renamed** in DataXray | The sync runs | The **same** Collibra asset (same UUID) is renamed; all relations from files and queries are preserved. Saved searches pick the new name up automatically on their next rerun (criteria are rebuilt from current names, never from stored text) | ✅ |
| CAT-3 | A classification is **deleted** in DataXray | The sync runs | Its Collibra asset is **retired** (status Obsolete), never deleted. Relations from file assets and saved queries survive as records | ✅ |
| CAT-4 | A previously deleted classification **reappears** in DataXray (same DataXray ID) | The sync runs | The retired asset is **reactivated** (status Candidate) — same asset, history intact | ✅ |
| CAT-4a | A classification is deleted in DataXray and a **new** one is created under the **same name** (new DataXray ID) | The sync runs | A **new** asset is created under the clean name; the old asset stays retired with its history and relations, renamed *Name (retired &lt;id fragment&gt;)* to free the name. A same-named asset that is not a retired DataXray classification is never taken over — the item fails with a message naming the clash | ✅ |
| CAT-5 | Two DataXray classifications share one name (e.g. two "VAT Number" annotators) | The sync runs | Both get distinct Collibra names, suffixed with their subtype (e.g. "VAT Number (Named Entity)"), falling back to type, then an id fragment | ✅ |
| CAT-6 | DataXray returns **zero** classifications (outage / truncated response) | The sync runs | The retirement step is **skipped** — a transient failure must never retire the whole catalog | ✅ |
| CAT-7 | An asset in the Classifications domain was created **by hand** (no sync tag) | The sync runs | It is never touched — not updated, not retired | ✅ |
| CAT-8 | The nightly sync hits any failure (unset token, DataXray down, every item failing) | The 02:00 timer fires | The run logs the reason and ends **cleanly** — it never throws, so the schedule is never disabled by Collibra's retry-then-disable rule | ✅ |
| CAT-9 | A regular Collibra user (no delete-capable responsibility on the domain) tries to delete a classification asset | They attempt the delete | Refused by Collibra's default permission model — asset deletion always requires an explicitly granted responsibility whose role includes asset removal, or Sysadmin. The integration grants no such responsibility; instances should not either (see D-2) | ✅ (Collibra default) |
| CAT-10 | A Sysadmin (or a user someone granted a delete-capable responsibility) hard-deletes a classification asset that still exists in DataXray | The next sync runs | The asset is recreated (matched by DataXray ID) — but relations from saved queries to the old asset are gone, so each of those searches silently loses that criterion and its next rerun returns *more* files (criteria are AND-ed; if it was the only criterion the rerun fails instead, FILE-4). Prefer letting the sync retire; treat the domain as sync-owned | ⚠️ documented limitation, see D-2 |

## 2. Per-file classification evidence

Owner: the import/rerun batch stage. "Evidence" = what a file's row in the
DataXray `/api/v1/files` response asserts.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| EVID-1 | A **label** is applied to a file (present on its result row) | The file is imported or re-synced | The file asset has a `groups` relation to the Label asset | ✅ |
| EVID-2 | A label is **removed from a file** (or deleted) in DataXray, so its row no longer lists it | The file is re-synced (rerun or nightly) | The file asset's `groups` relation to the label becomes **formerly groups** (if the integration created it, see EVID-9) | ✅ |
| EVID-3 | An **annotator** produced hits on a file (`uniquePhrases` > 0 / non-empty `annotations`) | The file is imported or re-synced | The file asset has a `groups` relation to the Annotator asset | ✅ |
| EVID-4 | An annotator was **checked but produced zero hits** on a file | The file is imported or re-synced | **No** relation is created — checked-with-zero is not a match, and an existing relation the integration created becomes **formerly groups** | ✅ |
| EVID-5 | An **extractor** produced metadata for a file (an `extractedMetadata` entry, any value) | The file is imported or re-synced | The file asset has a `groups` relation to the Extractor asset | 🔹 |
| EVID-6 | A search uses an extractor as a criterion | The query is composed | The DataXray query uses `extractedMetadata.name:"…"` (the `extractors.name` field matches nothing) | ✅ |
| EVID-7 | A file's row references a classification **not yet synced** into Collibra | The file is imported or re-synced | The link is skipped and counted as "unresolved" in the summary; running the classification sync and re-syncing the search resolves it | 🔹 |
| EVID-8 | A label was just unassigned in DataXray but its file rows **still report it** (indexing lag) | A rerun happens in that window | The relation is kept — Collibra always mirrors what the DataXray API asserts. It disappears on the first sync after DataXray stops reporting it. **Accepted by TRC** (see D-4) | ✅ (observed live) |
| EVID-9 | A person linked a file asset to a classification by hand (a `groups` relation Collibra records as created by that person) | The file is re-synced, whether or not DataXray reports that classification on the file | The person's link is never removed or changed, and no duplicate link is added when the file also matches that classification. Only links created by Collibra's **Workflow** user (what the integration's background tasks run as) are swapped. Caveat: links created by the customer's own Collibra workflows are also recorded as created by the Workflow user, so the integration cannot tell them apart from its own | ✅ |

## 3. Search & gated import

Owner: **Search DataXray**.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| IMP-1 | A user runs a search with any mix of labels/extractors/annotators and an optional annotated-text filter | The search completes | A query asset is created in **DataXray Sync Filter** holding: `groups` relations to each criterion, the Annotated Text Filter and DataXray Query attributes, a top-50 HTML preview, and the full result set attached as a zipped CSV | ✅ |
| IMP-2 | The results task is shown | The user does **not** tick "Import now…" | No file assets are created; the query asset and its attachments remain | ✅ |
| IMP-3 | A search matches **0** files | The results task is shown | The import controls are not offered | 🔹 |
| IMP-4 | The number of DataXray File assets in Collibra (wherever they live, any status) + this result set would exceed **25,000** | The results task is shown / import is forced via API | The import is refused with a message naming the projected total (enforced in script, not only in the form) | 🔹 |
| IMP-5 | A search matches more than **5,000** and at most **10,000** files (within cap) | The results task is shown | Import is offered with a duration warning | 🔹 |
| IMP-6 | The user ticks "Import now: create assets for all N matching files" | The import finishes | One file asset per result in **DataXray Files**: clean filename as display name, uniqueness-suffixed full name, File Path / File Size / Last Modified (readable, FILE-24) / Datasource Name / DataXray ID attributes, a `returns` relation from the query, and evidence-based classification relations. An Import Summary task reports created/updated/failed counts | ✅ |
| IMP-7 | A file was already imported by **another** query | This query's import runs | The **same** asset is reused (deterministic ID from the DataXray file id) and updated in place — never duplicated | ✅ |
| IMP-8 | DataXray stalls or drops the result stream mid-response | The search / import / rerun fetch runs | Up to **3 attempts** are made; on final failure nothing is saved (the transaction rolls back) and the error says so and advises retrying | ✅ |
| IMP-9 | The user ticks "Keep in sync nightly" and/or unticks "Create assets for new files found on later reruns" | The user clicks Send on the results task | Right away, whatever was chosen about importing, the query asset is tagged `dataxray-keep-in-sync` and/or `dataxray-no-auto-import`. Adding or removing either tag on the query asset by hand changes the behaviour from the next rerun | ✅ |
| IMP-10 | One import batch fails mid-run | The batch loop continues | The failed batch is counted and logged; remaining batches proceed — an import is never wedged by one bad batch | 🔹 |
| IMP-11 | A search matched at least one file | The user ticks "Keep in sync nightly" but **not** "Import now…" | The query asset is tagged `dataxray-keep-in-sync` and no file assets are created. The nightly reruns the search from then on | ✅ |
| IMP-12 | Cloud + Edge edition: a search matches more files than the preview shows (and no more than 10,000) | The search completes | The query asset carries `<search>-results.zip` with `results.csv` (header `Datasource,Path`, one row per matching file, same point-in-time snapshot as the preview's total). A search the preview already covers attaches the file with no extra request; one above 10,000 (or DataXray's `max_result_window`) attaches none and the results task says why; a failure while collecting it does not fail the search | ✅ |
| IMP-13 | A search matches more than **10,000** files (either edition) | The results task is shown / import is forced via API | Import and *Keep in sync nightly* are not offered; the task asks the user to refine the search. Completing the task through the API with Import now is refused with *"Importing is disabled for this search: it matched N file(s), more than the 10,000 a search can import …"* (enforced in script) | ✅ |

## 4. Rerun & file asset lifecycle

Owner: **Rerun DataXray Search** (from the query asset's page, or headless
via the nightly).

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| FILE-1 | A saved query's criteria were renamed in DataXray since it was created | The query is rerun | The query is rebuilt from the criteria's **current** names + the stored annotated-text filter; the DataXray Query attribute records what actually ran | ✅ |
| FILE-2 | A criterion's classification was deleted in DataXray (its asset is retired) | An **interactive** rerun starts | The rerun fails immediately with a message **naming the criterion** — it never silently drops an AND criterion and broadens the search (all criteria — including multiple values of the same kind — are AND-ed; the only OR is between the annotators the annotated text is searched in) | ✅ |
| FILE-3 | Same as FILE-2 | The **nightly** reruns that query | The run logs the reason, touches nothing, ends cleanly; other queries are unaffected | ✅ |
| FILE-4 | A query asset has no criteria left (relations removed) and no filter | A rerun starts | It fails/skips with "criteria cannot be reconstructed" — it never falls back to an all-files search | 🔹 |
| FILE-5 | A user starts Rerun on a **non-query** asset (e.g. a file asset) | The rerun starts | It refuses immediately, naming the asset's actual type (Collibra cannot hide the action on other asset pages when start roles are global roles) | ✅ |
| FILE-6 | A **new** file matches the query since last run | The query is rerun | A new file asset is created and linked | ✅ |
| FILE-7 | A matched file was **renamed/moved** in DataXray on a connector that keeps the file id (one that identifies files by the source system's own item id: SharePoint Online within one library, OneDrive, Google Drive, Gmail, Box) | The query is rerun | The same asset is updated: DataXray File Name and the other attributes refreshed; the asset's name and display name are not changed (FILE-26) | 🔹 |
| FILE-8 | A file **no longer matches** the query (changed, deleted, or no longer hits the criteria) | The query is rerun | The query's `returns` relation becomes **formerly returns**; the asset's status becomes **Not in any search** only if no other query returns it | ✅ |
| FILE-9 | A file is returned by **two** queries and one stops matching it | That query is rerun | The file stays active (Candidate) and keeps the other query's relation (see FILE-21) | ✅ |
| FILE-10 | A file asset with status **Not in any search** (or **Obsolete**, set by a release before v2.4) matches any query again | That query is rerun / imported | The same asset goes back to **Candidate** — history intact — and that query's `formerly returns` link, if any, becomes `returns` again | ✅ |
| FILE-11 | A rerun gets **0 results** from DataXray | The pass that swaps links and sets Not in any search would run | The pass is skipped entirely — a transient empty answer must never mark a query's whole population as no longer returned | ✅ |
| FILE-12 | A rerun's projected number of DataXray File assets (wherever they live) exceeds 25,000 | The rerun starts | Interactive: refused with the projection; headless: logged and skipped | 🔹 |
| FILE-13 | A rerun's summary task is left **open** in someone's inbox | The user revisits the query asset / the nightly fires | The Rerun action is still available and the nightly still runs (workflow exclusivity is UNCONSTRAINED) | ✅ |
| FILE-14 | DataXray reassigns **file ids** (e.g. datasource deleted and re-scanned) | The next sync runs | New assets are created and the old generation becomes **Not in any search** as orphans, keeping its history. Exception in the Cloud + Edge edition: a file whose object key (datasource id + object id) is unchanged keeps its asset (FILE-25). **Accepted by TRC** (see D-3) | 🔹 |
| FILE-15 | A matched file was **renamed or moved** on a connector that identifies files by **path** (SMB, local/NFS, SFTP, Amazon S3, Azure Blob, Google Cloud Storage, SharePoint on-prem), moved to another SharePoint Online library, or copied/moved to **another datasource** (any connector) | DataXray rescans and the query is rerun | DataXray has assigned a new file id, so this is FILE-14 for one file: a new asset is created in the DataXray Files domain, and the old asset gets a `formerly returns` link and Not in any search where it is (history kept; comments, attachments and a manual domain/community placement do not carry over) | 🔹 |
| FILE-16 | A user **moves** an active file asset to a domain in another community | The query is rerun | The asset is updated in place (attributes, classification and `returns` relations) and never moved back — the integration tracks file assets by UUID, not by domain (verified Sept 2026) | ✅ |
| FILE-17 | A **community or domain** holding file assets is deleted in Collibra (Collibra deletes its assets with it) | A query that still matches one of those files is rerun | The asset is recreated in the DataXray Files domain (same UUID). Only the rerun query's `returns` relation comes back; other queries' relations return as each of them reruns. Comments and history are gone (verified Sept 2026) | ✅ |
| FILE-18 | A file asset moved **out of** the DataXray Files domain (FILE-16) becomes orphaned because its query is deleted | The next nightly runs | The orphan sweep sets it to Not in any search where it is, and the 25,000 cap counts it — both work on every DataXray File asset by asset type, not on the Files domain (D-5, decided) | 🔹 |
| FILE-19 | A query asset is tagged `dataxray-no-auto-import` and new files match its search since the last run | The query is rerun (manually or by the nightly) | No file asset is created for files that have none; file assets that already exist (imported by any search) are refreshed and linked to this query with `returns`; statuses and links change as usual. The **Rerun complete** task reports the skipped files under *Not imported*. The 25,000 cap does not refuse the rerun, since it adds no assets | ✅ |
| FILE-20 | A person set a file asset's status to something other than Candidate, Not in any search or Obsolete (e.g. Accepted, Under Review) | A rerun or the nightly sweep finds that no query returns the file, or a search matches it again | The status is **never changed**. Attributes and relations are still refreshed and the `returns` / `formerly returns` and `groups` / `formerly groups` links are still swapped. A file asset a user has marked active is never made obsolete by DataXray | ✅ |
| FILE-21 | A file is returned by queries A and B, and A's search stops matching it | A is rerun | The file keeps its status (Candidate) and B's `returns` link; only A's link becomes `formerly returns`. The file becomes Not in any search only when no `returns` link is left (`formerly returns` links never count as returned) | ✅ |
| FILE-22 | A `returns` or integration-made `groups` link stops applying (FILE-8, EVID-2) | The rerun runs; later the link applies again (the search matches the file, or DataXray reports the classification again) | The link is swapped to **formerly returns** / **formerly groups** (shown as *formerly returned by* / *formerly grouped by* on the other asset), never deleted. When it applies again it is swapped back, so there is never both a link and its *formerly* twin | ✅ |
| FILE-23 | A rerun would create more than **10,000** new file assets (files the query does not return yet; on Cloud + Edge estimated from DataXray's total) | The rerun starts | Refused, naming the number, and nothing is changed: *"… more than the 10,000 one run may create. Refine the search, or tag it dataxray-no-auto-import …"*. A rerun that creates no new assets is not affected | ✅ |
| FILE-24 | Any import or rerun writes a file asset's attributes | It finishes | **File Size** reads like `27.2 KB (27,885 bytes)` (1 KB = 1,024 bytes; under 1 KB just `512 bytes`) and **Last Modified** like `10 Apr 2025, 17:22 UTC`. A value DataXray sends in another shape is kept as is. Assets written by an earlier release change to this format the next time they sync | ✅ |
| FILE-26 | A person renames a file asset in Collibra (name or display name) | Any later import or rerun syncs the file | The asset keeps the person's name. The **DataXray File Name** attribute holds DataXray's current filename and is refreshed on every sync, like the other attributes; a value a person types into any attribute is overwritten. A new asset is named from DataXray's filename when it is created | ✅ |
| FILE-27 | An admin has turned on extractor values (ADM-6) and a file has extractor results in DataXray | The file is imported or rerun | Its **DataXray Extracted Values** attribute holds a table of extractor name and value(s), as text, sorted by extractor; each value cut to 2,000 characters (on-prem) / 500 (Cloud + Edge), a file's values to 50,000 / 10,000 characters with a last row saying how many were left out. When the file no longer has extractor values, the attribute is cleared | ✅ |
| FILE-25 | Cloud + Edge edition: a file's DataXray file id changes but its datasource and object id do not (the datasource's scanned data was dropped from the index and the datasource scanned again) | A search is imported or rerun | The existing asset is found by its **DataXray Datasource ID** + **DataXray Object ID** attributes and updated in place (DataXray ID takes the new id); no new asset is created and the asset keeps its query links, comments, attachments and community. Every asset a sync writes stores both attributes; on-prem rows carry no object id and leave them unset | ✅ |

## 5. Query asset lifecycle (Collibra side)

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| QRY-1 | A saved query is **deleted** in Collibra | The next nightly (02:30) runs | Its file assets are **not deleted**. Files no other query returns get **Not in any search** from the orphan sweep (unless a person set their status); shared files are untouched. The query's attached results CSV and history are gone with it | ✅ |
| QRY-2 | A deleted query was tagged keep-in-sync | The next nightly runs | Its nightly sync simply stops (the tag died with the asset) — no errors, other queries unaffected | ✅ |
| QRY-3 | A saved query is **retired/archived** (status Obsolete) but still tagged | The next nightly runs | The query is **skipped** (logged, counted) — archiving a query is the supported way to pause its sync while keeping it restorable. Reactivating the query resumes the nightly sync; a **manual** rerun from its page still works (explicit user intent) | ✅ |
| QRY-4 | The keep-in-sync tag is **removed** from a query | The next nightly runs | The query is no longer rerun; its file assets stay exactly as they are (they are still "returned" by the query, so the sweep never touches them) | ✅ |
| QRY-5 | A query asset is **renamed** in Collibra | Anything runs | No effect — all references are by UUID. (The next rerun's summary shows the new name) | 🔹 |
| QRY-6 | A user deletes a query and wants its exclusive files **gone**, not just Not in any search | — | Supported as a manual/admin action only (bulk delete in the Files domain). The automated lifecycle never deletes | ✅ |

## 6. Nightly automation

Owner: **Sync DataXray Classification (Nightly)** at 02:00 and
**Sync DataXray Files (Nightly)** at 02:30.

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| NIGHT-1 | Both nightlies are enabled | A night passes | Classifications sync at 02:00 **before** file reruns at 02:30, so renames/retirements are current before queries are rebuilt | 🔹 |
| NIGHT-2 | Several queries are tagged keep-in-sync and one of them fails (e.g. dead criterion) | The 02:30 nightly runs | Each query runs in its **own** headless workflow instance; one failure never blocks the others; per-query outcomes are in dgc.log | ✅ |
| NIGHT-3 | DataXray File assets exist, anywhere in Collibra, that **no query returns** (deleted query, or a failed relation add) | The 02:30 nightly runs | The orphan sweep sets them to **Not in any search**. Files already Not in any search, and files whose status a person set, are skipped | ✅ |
| NIGHT-4 | The relation scan fails partway during the sweep | The sweep would change statuses | The sweep **aborts for the night** — it never acts on a partial picture | 🔹 |
| NIGHT-5 | The Rerun workflow isn't deployed, or config is missing | The 02:30 nightly fires | The fan-out is skipped with a logged reason; the orphan sweep still runs; the timer schedule survives | 🔹 |
| NIGHT-6 | A rerun-summary task from a manual run is open overnight | The 02:30 nightly fires | The nightly's headless rerun of that query still starts and completes (no user task in headless mode, no exclusivity conflict) | ✅ |
| NIGHT-8 | Cloud + Edge edition: a file asset is **Not in any search** and DataXray no longer has the file (deleted in DataXray, or its whole datasource deleted) | The 02:30 nightly runs | The existence check looks it up by DataXray Datasource ID + Object ID (about 200 per request) and sets **Not in DataXray**. Files DataXray still has are unchanged | ✅ |
| NIGHT-9 | Cloud + Edge edition: a **Not in DataXray** file asset's file is back in DataXray | The nightly runs | It goes back to **Not in any search**; a search that matches it sets it to **Candidate** | ✅ |
| NIGHT-10 | A file asset no search returns has a status a person set (e.g. Accepted), or has no object key (Not in any search since before the upgrade) | The nightly runs | Not checked, nothing changes; the number without an object key is logged | ✅ |
| NIGHT-7 | The workflows were upgraded from a release before v2.4, and file assets that no query returns still have status **Obsolete** | The first 02:30 nightly after the upgrade (and after Configure has been re-run, ADM-4) runs | The orphan sweep moves every such file asset to **Not in any search** (a one-off migration); after that Obsolete is never set on file assets | ✅ |

## 7. Deployment & configuration lifecycle

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| ADM-1 | A new version of a workflow is **re-imported** (same process ID) | The upsert completes | The admin-set Base URL/token, start roles, exclusivity and enablement are all **preserved** — a normal upgrade needs no reconfiguration | ✅ |
| ADM-2 | A workflow is **deleted and re-imported** | The import completes | Its config variables and start roles reset — re-enter the token and **re-run Configure** | ✅ (documented) |
| ADM-3 | Configure is re-run at any time | It completes | Idempotent: existing elements untouched, missing ones created, assignments augmented (unless they carry hand-tuned rules, which are reported and skipped) | ✅ |
| ADM-4 | The v2.4 workflows were re-imported over an earlier release | Configure is re-run once by an admin | It creates the **Not in any search** status and the **formerly returns** and **formerly groups** relation types and adds them to the asset type assignments; running it again changes nothing. Until it has run, **Rerun DataXray Search** refuses to start with a message saying so, and the nightly orphan sweep is skipped (logged). An assignment with hand-tuned articulation/validation rules is reported and left alone: the relations still work but do not show on that asset type's page until added in Settings > Operating model > Asset types > Assignments | ✅ |
| ADM-5 | An installation from before the DataXray rename (elements named *Data X-Ray …*, queries domain *Data X-Ray Custom Queries*) | The new workflows are re-imported and Configure is run | Every element that still has its old default name is renamed (community, domains with the queries domain becoming **DataXray Sync Filter**, the File asset type, the DataXray ID / Index ID / Query attribute types, both roles, the six workflow definitions; from v2.7 also the *Unstructured Data Query* asset type, which becomes **DataXray Sync Filter**). An element renamed by hand keeps its name. IDs, settings, run permissions and tags are unchanged | ✅ |
| ADM-6 | Configure's **Sync extractor values to file assets** variable is `false` (the default) | Configure runs and files are synced | No **DataXray Extracted Values** attribute type exists, nothing is written. Set to `true` and Configure run: the attribute type is created and added to the **DataXray File** assignment, and syncs fill it (FILE-27). Set back to `false` and Configure run: the attribute type is deleted, which removes it from the assignment and deletes every value | ✅ |

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
  removes that criterion from saved queries that used it (so they return more
  files) — prefer sync-retire.
- ~~**D-3 (FILE-14): file identity is the DataXray file id.**~~ **Accepted by
  TRC** (August 2026): "old generation retired with history, new generation
  starts clean." If DataXray reassigns file ids (e.g. a datasource is deleted
  and re-scanned), Collibra creates new assets and the old generation gets
  Not in any search (Obsolete before v2.4) — records preserved, no migration
  attempted. **Since v2.6 (Cloud + Edge):** a file whose datasource id and
  object id are unchanged keeps its asset (FILE-25), which covers a
  datasource's data being dropped from the index and rescanned (agreed with
  TRC, Oct 2026). (A content-hash
  based migration remains possible later if ever needed.)
  Which events assign a new file id depends on the DataXray connector
  (verified in the DataXray source, Sept 2026): connectors that identify
  files by path (SMB, local/NFS, SFTP, S3, Azure Blob, GCS, SharePoint
  on-prem) assign one on every rename or move; connectors that use the source
  system's item id (SharePoint Online within a library, OneDrive, Google
  Drive, Gmail, Box) keep it. Every connector assigns a new id when a file is
  copied or moved to another datasource, or a datasource is deleted and
  re-scanned. See FILE-7 and FILE-15.
- ~~**D-4 (EVID-8): evidence freshness is bounded by DataXray indexing.**~~
  **Accepted by TRC** (August 2026), with the agreed wording: "Collibra
  reflects DataXray as of the last sync; changes in DataXray appear after
  DataXray has re-indexed and the next sync/rerun has run." Worst case with
  nightly-only syncing is about a day of lag; an on-demand rerun closes it
  immediately. The integration deliberately never second-guesses the source
  system.

- ~~**D-5 (FILE-18): file assets moved out of the Files domain.**~~
  **Decided & implemented** (v2.4, October 2026). Moving file assets into
  other communities is supported (FILE-16); the nightly orphan sweep and the
  25,000 cap now work on every DataXray File asset by asset type, so a moved
  asset is swept and counted wherever it lives.

All decisions are closed — D-1, D-2 and D-5 decided and implemented/rolled
back as recorded above, D-3 and D-4 accepted by TRC as intended behavior.
