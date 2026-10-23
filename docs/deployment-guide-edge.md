# Deploying the DataXray workflows — Collibra Cloud + Edge edition

This guide is for the **`collibra-data-xray-workflows-cloud-edge-<version>.zip`** bundle: Collibra Cloud reaching an on-premises (or otherwise firewalled) DataXray through a **Collibra Edge site**. If Collibra can reach your DataXray directly over HTTPS, use the on-prem edition and [deployment-guide.md](deployment-guide.md) instead.

Everything is done through the Collibra and DataXray user interfaces; no command line is required.

## How it works

The workflows never open a network connection themselves. Every call to DataXray is a Collibra **External API task**, which Collibra hands to your Edge site; the Edge site makes the HTTP request inside your network using an **HTTP connection** that holds the DataXray host and credentials, and returns the response to the workflow. The workflows only need to know the **name** of that connection.

Because an External API task is asynchronous and its response is limited in size, the Edge edition fetches results **one page at a time** (25 files per request by default) and imports each page as it arrives. A search that matches thousands of files therefore takes roughly one to two seconds per 25 files; the nightly sync runs the same way, unattended.

## Prerequisites

| # | Requirement | How to check |
|---|---|---|
| 1 | **Collibra Cloud** (Collibra Data Intelligence Platform, cloud-hosted), with the Workflow Designer / workflow import feature. Tested against Collibra 2026.09. | Settings → Workflows → Definitions shows an **Upload** button. |
| 2 | An **Edge site** that is installed, **healthy**, and has **network access to DataXray** on its HTTPS port. Tested against Edge 2026.6. | Settings → Edge → the site's status is *Healthy*. From the Edge host, `curl -sI https://<data-x-ray-host>/` answers. |
| 3 | A **DataXray user** for the integration: a local (username + password) account with access to every datasource the workflows should see. The Edge connection authenticates with HTTP Basic auth using this account. Tested against DataXray 7.x. | Log in to DataXray with the account and open the datasources. |
| 4 | A Collibra global role with **Manage connections and capabilities** (or System administration) to create the HTTP connection. Note: the `DataXray Admin` role created by the configure workflow does **not** include this permission. | Settings → Edge → the site → **Connections** → *Create connection* is available. |
| 5 | The six ZIPs from the `cloud-edge` bundle. | The bundle's file names end in `.zip` and there is no `-onprem-` in the bundle name. |

## Step 1 — Create the HTTP connection on the Edge site

1. In Collibra go to **Settings → Edge**, open your Edge site and its **Connections** tab.
2. Click **Create connection** and choose the **HTTP** connection type.
3. Fill in:
   - **Name**: `data-xray` (the workflows use this name by default; any other name works if you set it on each workflow in Step 4).
   - **Host**: the DataXray base URL as your browser sees it, for example `https://dataxray.example.com` (no trailing path).
   - **Authentication**: **Basic** — the DataXray integration account's username and password. (DataXray also accepts OAuth 2.0 client-credentials only if your DataXray is configured for it; Basic is the tested path.)
   - **Test connection path** (optional): `/api/v1/classifications`.
4. Save and click **Test connection**. A green result means the Edge site can reach DataXray with those credentials.

The same connection is shared by all four DataXray-calling workflows.

## Step 2 — Import the six workflow ZIPs

Settings → Workflows → Definitions → **Upload**, once per ZIP. Order does not matter. The process ids are identical to the on-prem edition, so importing the Edge edition over an existing on-prem installation **replaces it in place** and keeps the start-role settings. Collibra drops configuration variables the new edition does not declare, so the Bearer token must be re-entered if you ever switch back to the on-prem edition.

## Step 3 — Enable each workflow and run *Configure DataXray Workflows*

Enable each definition (▶), then start **Configure DataXray Workflows** once as a Collibra administrator. It creates the DataXray operating model and access roles exactly as in the on-prem edition. Re-run it whenever you import a workflow after the first run.

## Step 4 — Set the configuration variables

Settings → Workflows → Definitions → select a workflow → **Variables**:

| Workflow | Variable | Value |
|---|---|---|
| Search DataXray, Rerun DataXray Search, Sync DataXray Classifications, Sync DataXray Classification (Nightly), Sync DataXray Files (Nightly) | **Edge HTTP connection name (DataXray)** | The connection name from Step 1 (`data-xray` unless you chose another). |
| Search DataXray, Rerun DataXray Search | **DataXray results per request (1-100)** | Leave at `25`. Lower it only if a DataXray page exceeds Collibra's response limit (see *Limits*). |
| all four | **DataXray Base URL (deep links only)** | Optional. Used only to build links from Collibra assets back to DataXray; leave the placeholder to skip links. |

There is **no Bearer token** in this edition: credentials live on the Edge connection.

## Step 5 — Assign roles and run

Identical to the on-prem edition: members of `DataXray User` may run the search and sync workflows; `DataXray Admin` and Sysadmins edit the variables above.

Run **Sync DataXray Classifications** first — it is mandatory in this edition: searches and reruns translate classifications to DataXray's internal ids stored by that sync (attribute *DataXray Index ID*), and refuse to run for a classification it has not synced. Then **Search DataXray** from the **+ Create** menu. Results appear in your task inbox after a few seconds (the search is asynchronous in this edition). Importing and reruns page through the results; the nightly jobs run unattended at 02:00 and 02:30 server time.

The search results task works as in the on-prem edition (see *Using the workflows* in [deployment-guide.md](deployment-guide.md#using-the-workflows)): three independent options, **Import now**, **Keep in sync nightly** (tag `dataxray-keep-in-sync`) and **Create assets for new files found on later reruns** (unticked: tag `dataxray-no-auto-import`, so reruns only refresh file assets already in Collibra). A file asset that no saved search returns any more gets the status **Not in any search**; links that stop applying become *formerly returns* / *formerly groups*. A status a person set and links people created are never changed. [file-asset-lifecycle.md](file-asset-lifecycle.md) has the details.

## Who can see what

The same guidance as the on-prem edition applies:

- The workflows refer to the **Collibra to DataXray Integration** community and its three domains only by their fixed IDs, and Configure never renames them. Renaming the community or the domains in Collibra is safe.
- **Keep the DataXray Classifications domain readable by everyone** who works with file or query assets. A user who cannot view a classification asset cannot see relations to it either (Collibra: "View permissions also impact asset relations"), so classifications moved into a restricted community silently disappear from file assets. Do not move classification assets out of this domain: the classification sync only looks for them there.
- **File assets and saved searches can be private.** Set view permissions on the **DataXray Files** and **DataXray Sync Filter** domains (view permissions can be set per domain, not only per community); no separate community is needed. Restrict the queries domain as well, because a query's *returns* links list file names. A user who cannot view a file asset does not see it through a query asset's relations either.
- **Moving file assets to another community is safe.** They keep syncing wherever they are: the integration's background tasks run as Collibra's **Workflow** user, which has no permission restrictions, and the check for an existing asset covers the whole instance, so no duplicate is created in the Files domain. See [Can file assets be moved to another community?](file-asset-lifecycle.md#can-file-assets-be-moved-to-another-community).
- **Extractor values (optional, off by default)** can be the sensitive data itself. If you turn them on (see [Optional: extractor values on file assets](file-asset-lifecycle.md#optional-extractor-values-on-file-assets)), make sure the **DataXray Files** domain, and any community file assets were moved to, is readable only by the people allowed to see those values.

## Upgrading from Data X-Ray to DataXray names

Releases before the DataXray rename call the product **Data X-Ray** and the queries domain **Data X-Ray Custom Queries**. After re-importing the workflows, run **Configure DataXray Workflows** once: it renames the community, the three domains (the queries domain becomes **DataXray Sync Filter**), the **DataXray File** asset type, the **DataXray ID**, **DataXray Index ID** and **DataXray Query** attribute types, the **DataXray Admin** and **DataXray User** roles and the six workflow definitions. It only renames an element that still has its old default name: one you renamed yourself keeps your name. The workflows refer to all of these by ID, so nothing else changes: settings, run permissions, tags (`dataxray-keep-in-sync`, `dataxray-no-auto-import`) and the Edge connection name (`data-xray` by default) stay as they are.

## Upgrading to v2.8

Re-import the workflows. Nothing else is needed: v2.8 adds one optional feature, extractor values on file assets, which is off until an admin sets **Configure DataXray Workflows**' new **Sync extractor values to file assets (true/false)** variable to `true` and runs it. See [Optional: extractor values on file assets](file-asset-lifecycle.md#optional-extractor-values-on-file-assets).

## Upgrading to v2.7

Re-import the workflows, then **run Configure DataXray Workflows once** as an admin. It creates the **DataXray File Name** attribute type and adds it to the **DataXray File** asset type, and renames the **Unstructured Data Query** asset type to **DataXray Sync Filter** (only if it still has that name). Until it has run, **Rerun DataXray Search** refuses to start with a message saying so. From v2.7 a sync no longer renames existing file assets: the asset name is set from DataXray's filename when the asset is created and can then be changed in Collibra, and DataXray's current filename is kept in **DataXray File Name**.

v2.7 also adds the **Not in DataXray** status (Configure creates it and adds it to the **DataXray File** asset type) and a nightly check that uses it. **Sync DataXray Files (Nightly)** now has an **Edge HTTP connection name (DataXray)** setting of its own (default `data-xray`): check it after importing. Every night, after the reruns and the orphan sweep, it asks DataXray about each file asset that is **Not in any search**, by its DataXray Datasource ID and Object ID. A file DataXray no longer has, because the file or its whole datasource was deleted there, gets **Not in DataXray**. A Not in DataXray file that DataXray has again goes back to Not in any search. A status a person set is never changed. File assets that were already Not in any search before the upgrade carry no object ID and are not checked. See *How are file assets orphaned?* in the file asset lifecycle guide.

## Upgrading to v2.6

Re-import the workflows, then **run Configure DataXray Workflows once** as an admin. It creates the **DataXray Datasource ID** and **DataXray Object ID** attribute types and adds them to the **DataXray File** asset type; until it has run, **Rerun DataXray Search** refuses to start with a message saying so. Every sync from then on stores both on the file assets it writes, and uses them to recognise a file that DataXray has given a new id (see *What counts as the same file?* in the file asset lifecycle guide). Rerun your searches once (or let the nightly sync run) before you next drop a datasource's data from DataXray's index, so that assets imported by earlier releases have them too.

## Upgrading to v2.4

Re-import all six workflows (in place; the connection name, other variables and run permissions are kept), then **run Configure DataXray Workflows once** as an admin. It creates the **Not in any search** status and the **formerly returns** and **formerly groups** relation types, and adds them to the asset type assignments. Until it has run, **Rerun DataXray Search** refuses to start with a message saying so, and the nightly file sync skips its orphan sweep (logged in `dgc.log`). If Configure reports that it left an assignment alone because it has hand-tuned rules, the new relations still work but do not show on that asset type's page; add them in **Settings > Operating model > Asset types > Assignments**. The first nightly file sync after the upgrade moves file assets that earlier releases set to **Obsolete** because no search returned them to **Not in any search**.

## Limits specific to the Edge edition

- **Response size**: Collibra caps an External API response at **100 KB**. A page of 25 results is typically 30–40 KB, but rows grow with the number of annotators and extractors that match a file. When Collibra refuses a page as too large, the workflow asks DataXray for fewer results per page and carries on (a warning in `dgc.log` records the new size), so searches, imports and reruns still complete, only with more round trips. If that happens often, lower **DataXray results per request** to save the extra requests. A file whose result alone exceeds 100 KB is **skipped**: the import or rerun carries on without it, counts it as *failed* in its summary, and `dgc.log` names its DataXray file id. A rerun never sets a skipped file to *Not in any search*.
- **Large annotator catalogues**: the classification sync fetches the complete annotator list in one request when it fits (roughly up to 200 annotators). Beyond that it falls back to the annotators that currently have findings, fetching each unknown one individually (about one second each, once), and it then **never retires annotators** — a deleted annotator stays *Candidate* in Collibra until the catalogue fits again or it is retired by hand. Labels and extractors are always synced completely. The sync result reports when this fallback was used.
- **Results per query**: DataXray pages results with an offset and cannot go past its `max_result_window` (10,000 rows by default, 30,000 on some installations). A search matching more files than that can be previewed but not imported; narrow it (add a label or annotator) or split it per datasource. Independently of DataXray's setting, no search imports more than 10,000 files (as in the on-prem edition).
- **Latency**: each request through Edge takes roughly one second. Importing 5,000 files is ~100 requests, a couple of minutes.
- **Results file**: like the on-prem edition, a search attaches its full result set to the query asset as a ZIP'd CSV (`<search>-results.zip`, one row per file: datasource and path). Through Edge it is collected after the preview, 100 files per request, so a search matching 5,000 files takes about 50 more requests. A search matching more than 10,000 files (or more than DataXray's `max_result_window`) gets no results file, and the results task says so; importing is not affected. If the file could not be built (for example DataXray stopped answering part-way), the search still completes and the results task says the file is missing. A file whose result alone was too large to send through Edge is left out of the CSV and named in `dgc.log`.

## Which version is installed?

Every workflow states the release it came from, as *DataXray workflows vX.Y.Z (onprem)* or *(cloud-edge)*:

- at the end of its description on its overview page (Settings → Workflows → Definitions → select the workflow), also returned by the REST API (`GET /rest/2.0/workflowDefinitions/<id>`);
- in small print at the bottom of its result, summary and failure tasks;
- in the first `dgc.log` line of every run, and on the log line of any request that finally failed.

Check all six after an upgrade: a workflow that was not re-imported still shows its old version. (Workflows imported before v2.3.0 show no version.)

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The workflow instance shows an **error** right after starting and never reaches a task. | The **connection name** does not match an HTTP connection on any Edge site (Collibra rejects the External API task before sending it). | Correct the variable, cancel the errored instance (Settings → Workflows → Instances), start again. |
| Task **Search Failed** / **Sync Failed** with *Edge request failed* or *HTTP 401/403*. | The Edge site cannot reach DataXray, or the connection's credentials are wrong or the account lacks permissions. | Test the connection in Settings → Edge; check the Edge host's network path; check the DataXray account. |
| *… is not visible to DataXray through this connection …* | The classification was synced with a different DataXray account (another organisational unit) or its DataXray ID is stale. | Run **Sync DataXray Classifications** through this connection, then search again. |
| `dgc.log` shows *HTTP server error: status 500* for the External API task, and a search or rerun was retried with fewer results per request. | Collibra refused a DataXray response that was too large for it and reported this as an HTTP 500 with no body (DataXray itself answered normally). From v2.3.0 the workflow treats it like any oversized page and carries on with fewer results per request. | Nothing, unless it happens on every run: then lower **DataXray results per request** to save the extra requests. |
| Task **Search Failed**, or a rerun aborted, with *Even a one-file request to DataXray failed … not a response-size problem*. | Even the smallest possible request failed, so DataXray or the Edge connection is unavailable. | Check that DataXray is up and test the HTTP connection in Settings → Edge; run the search again later. |
| An import or rerun summary counts *failed* files, and `dgc.log` has *skipped result N (DataXray file …) — on its own it is larger than Collibra allows* | That file's search result alone exceeds 100 KB, so Collibra cannot receive it through Edge. It is skipped; everything else is imported. An existing asset for it is left as it was and does not get *Not in any search*. | Contact Ohalo support with the file id from the log. |
| *Cannot search: … was deleted in DataXray (its Collibra asset is retired).* | A classification picked in the form was deleted in DataXray; the sync marked its Collibra asset Obsolete, but the pickers still offer it (they cannot filter by status). | Remove it from the search. |
| *Cannot search: … has no DataXray Index ID in Collibra.* | The classification has no numeric index id yet: the classification sync has not run through Edge, or the standalone script ran without `--stamp-index-ids`. | Run **Sync DataXray Classifications**, or re-run the standalone script with `--stamp-index-ids` (it updates the existing assets in place), then search again. |
| A rerun stops with *This instance is missing the status 'Not in any search' …* | The workflows were upgraded to v2.4 but Configure has not been re-run. | Run **Configure DataXray Workflows** once (see *Upgrading to v2.4*). |
| Nightly jobs do nothing. | The connection name is unset on **Sync DataXray Classification (Nightly)** or on **Rerun DataXray Search** (they skip cleanly and log why). **Sync DataXray Files (Nightly)** starts headless reruns, which use the connection set on Rerun DataXray Search; its own connection setting is only used for the check that sets *Not in DataXray*. | Set the variable on all three workflows. |

Full detail for every run is written to Collibra's `dgc.log` (ask Collibra support for access on Cloud).
