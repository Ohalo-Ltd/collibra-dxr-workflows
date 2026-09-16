# Deploying the Data X-Ray workflows — Collibra Cloud + Edge edition

This guide is for the **`collibra-data-xray-workflows-cloud-edge-<version>.zip`** bundle: Collibra Cloud reaching an on-premises (or otherwise firewalled) Data X-Ray through a **Collibra Edge site**. If Collibra can reach your Data X-Ray directly over HTTPS, use the on-prem edition and [deployment-guide.md](deployment-guide.md) instead.

Everything is done through the Collibra and Data X-Ray user interfaces; no command line is required.

## How it works

The workflows never open a network connection themselves. Every call to Data X-Ray is a Collibra **External API task**, which Collibra hands to your Edge site; the Edge site makes the HTTP request inside your network using an **HTTP connection** that holds the Data X-Ray host and credentials, and returns the response to the workflow. The workflows only need to know the **name** of that connection.

Because an External API task is asynchronous and its response is limited in size, the Edge edition fetches results **one page at a time** (50 files per request by default) and imports each page as it arrives. A search that matches thousands of files therefore takes a few seconds per 50 files; the nightly sync runs the same way, unattended.

## Prerequisites

| # | Requirement | How to check |
|---|---|---|
| 1 | **Collibra Cloud** (Collibra Data Intelligence Platform, cloud-hosted), with the Workflow Designer / workflow import feature. Tested against Collibra 2026.09. | Settings → Workflows → Definitions shows an **Upload** button. |
| 2 | An **Edge site** that is installed, **healthy**, and has **network access to Data X-Ray** on its HTTPS port. Tested against Edge 2026.6. | Settings → Edge → the site's status is *Healthy*. From the Edge host, `curl -sI https://<data-x-ray-host>/` answers. |
| 3 | A **Data X-Ray user** for the integration: a local (username + password) account with access to every datasource the workflows should see. The Edge connection authenticates with HTTP Basic auth using this account. Tested against Data X-Ray 7.x. | Log in to Data X-Ray with the account and open the datasources. |
| 4 | A Collibra global role with **Manage connections and capabilities** (or System administration) to create the HTTP connection. Note: the `Data X-Ray Admin` role created by the configure workflow does **not** include this permission. | Settings → Edge → the site → **Connections** → *Create connection* is available. |
| 5 | The six ZIPs from the `cloud-edge` bundle. | The bundle's file names end in `.zip` and there is no `-onprem-` in the bundle name. |

## Step 1 — Create the HTTP connection on the Edge site

1. In Collibra go to **Settings → Edge**, open your Edge site and its **Connections** tab.
2. Click **Create connection** and choose the **HTTP** connection type.
3. Fill in:
   - **Name**: `data-xray` (the workflows use this name by default; any other name works if you set it on each workflow in Step 4).
   - **Host**: the Data X-Ray base URL as your browser sees it, for example `https://dataxray.example.com` (no trailing path).
   - **Authentication**: **Basic** — the Data X-Ray integration account's username and password. (Data X-Ray also accepts OAuth 2.0 client-credentials only if your Data X-Ray is configured for it; Basic is the tested path.)
   - **Test connection path** (optional): `/api/v1/classifications`.
4. Save and click **Test connection**. A green result means the Edge site can reach Data X-Ray with those credentials.

The same connection is shared by all four Data X-Ray-calling workflows.

## Step 2 — Import the six workflow ZIPs

Settings → Workflows → Definitions → **Upload**, once per ZIP. Order does not matter. The process ids are identical to the on-prem edition, so importing the Edge edition over an existing on-prem installation **replaces it in place** and keeps every configuration variable and start-role setting.

## Step 3 — Enable each workflow and run *Configure Data X-Ray Workflows*

Enable each definition (▶), then start **Configure Data X-Ray Workflows** once as a Collibra administrator. It creates the Data X-Ray operating model and access roles exactly as in the on-prem edition. Re-run it whenever you import a workflow after the first run.

## Step 4 — Set the configuration variables

Settings → Workflows → Definitions → select a workflow → **Variables**:

| Workflow | Variable | Value |
|---|---|---|
| Search Data X-Ray, Rerun Data X-Ray Search, Sync Data X-Ray Classifications, Sync Data X-Ray Classification (Nightly) | **Edge HTTP connection name (Data X-Ray)** | The connection name from Step 1 (`data-xray` unless you chose another). |
| Search Data X-Ray, Rerun Data X-Ray Search | **Data X-Ray results per request (1-200)** | Leave at `50`. Lower it only if a Data X-Ray page exceeds Collibra's response limit (see *Limits*). |
| all four | **Data X-Ray Base URL (deep links only)** | Optional. Used only to build links from Collibra assets back to Data X-Ray; leave the placeholder to skip links. |

There is **no Bearer token** in this edition: credentials live on the Edge connection.

## Step 5 — Assign roles and run

Identical to the on-prem edition: members of `Data X-Ray User` may run the search and sync workflows; `Data X-Ray Admin` and Sysadmins edit the variables above.

Run **Sync Data X-Ray Classifications** first, then **Search Data X-Ray** from the **+ Create** menu. Results appear in your task inbox after a few seconds (the search is asynchronous in this edition). Importing and reruns page through the results; the nightly jobs run unattended at 02:00 and 02:30 server time.

## Limits specific to the Edge edition

- **Response size**: Collibra caps the size of an External API response (about 500 KB on Collibra 2026.09; Collibra documents 100 KB). A page of 50 results is ~60–80 KB. If a page ever exceeds the cap, the workflow reports *Response size exceeds the allowed limit* — lower **results per request**.
- **Results per query**: Data X-Ray pages results with an offset and cannot go past its `max_result_window` (10,000 rows by default, 30,000 on some installations). A search matching more files than that can be previewed but not imported; narrow it (add a label or annotator) or split it per datasource.
- **Latency**: each request through Edge takes roughly one second. Importing 5,000 files is ~100 requests, a couple of minutes.
- **No results file**: the on-prem edition attaches the full result set as a ZIP'd CSV; the Edge edition shows a preview and imports instead.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The workflow instance shows an **error** right after starting and never reaches a task. | The **connection name** does not match an HTTP connection on any Edge site (Collibra rejects the External API task before sending it). | Correct the variable, cancel the errored instance (Settings → Workflows → Instances), start again. |
| Task **Search Failed** / **Sync Failed** with *Edge request failed* or *HTTP 401/403*. | The Edge site cannot reach Data X-Ray, or the connection's credentials are wrong or the account lacks permissions. | Test the connection in Settings → Edge; check the Edge host's network path; check the Data X-Ray account. |
| *… is not visible to Data X-Ray through this connection …* | The classification was synced with a different Data X-Ray account (another organisational unit) or its Data X-Ray ID is stale. | Run **Sync Data X-Ray Classifications** through this connection, then search again. |
| *Response size exceeds the allowed limit.* | A results page is larger than Collibra allows. | Lower **Data X-Ray results per request**. |
| Nightly jobs do nothing. | The connection name is unset on the nightly definitions (they skip cleanly and log why). | Set the variable on both nightly workflows. |

Full detail for every run is written to Collibra's `dgc.log` (ask Collibra support for access on Cloud).
