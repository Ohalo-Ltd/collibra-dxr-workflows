# DataXray Workflows — Deployment & Configuration Guide

This guide explains how to deploy and configure the DataXray workflows in
Collibra entirely through the Collibra user interface. You will be provided with
**six Workflow Designer ZIP files**, one per workflow. No command-line tools or
scripts are required — everything below is done from within Collibra.

## Which edition do I have?

Each release ships **two bundles** with the same six workflows:

| Bundle | Use it when | Guide |
|---|---|---|
| `collibra-data-xray-workflows-onprem-<version>.zip` | Collibra (self-hosted **or** Cloud) can reach your DataXray directly over HTTPS. The workflows call DataXray themselves with a Bearer token. | **This guide.** |
| `collibra-data-xray-workflows-cloud-edge-<version>.zip` | Collibra **Cloud** and a DataXray that is only reachable from inside your network. Calls go through a **Collibra Edge site** and an HTTP connection that holds the credentials. | [deployment-guide-edge.md](deployment-guide-edge.md) — Steps 1–3 and 5 below still apply; Step 4 differs. |

Both editions use the same process ids, so you can switch from one to the other later by importing the other bundle over the installed workflows; you will need to re-enter the variables the new edition uses (the Bearer token for on-prem, the connection name for Edge), because Collibra drops variables a redeployed workflow no longer declares.

## The six workflows

| Workflow | What it does | How it runs |
|---|---|---|
| **Configure DataXray Workflows** | One-time setup. Creates the community, domains, asset types, attribute types, relation types, the **Not in any search** status and the assignments the other workflows rely on, creates the two access-control roles, and sets who is allowed to run each workflow. Idempotent — safe to run again. | From the **+ Create** menu. Run once after importing. |
| **Search DataXray** | Builds a DataXray search query from Collibra classifications (every selected label, extractor and annotator must match — criteria are always AND-ed; the optional annotated text may appear in any of the annotators chosen for it), previews the matching files, and offers to **import all results as file assets**, to **keep the search in sync nightly**, and to let later reruns **create assets for new files** (three independent options). | From the **+ Create** menu, on demand. |
| **Rerun DataXray Search** | Reruns a saved search from its query asset and re-syncs its file assets: new files imported, changed files updated, files no search returns any more set to **Not in any search** (never deleted). | From an **Unstructured Data Query** asset's page; also run headlessly by the nightly file sync. |
| **Sync DataXray Classifications** | Syncs classifications from DataXray into Collibra, on demand. Classifications that disappear from DataXray are **retired**, not deleted. | From the **+ Create** menu, on demand. |
| **Sync DataXray Classification (Nightly)** | The same classification sync, run automatically every night at **02:00** (server time). | Runs on a timer; no manual start. |
| **Sync DataXray Files (Nightly)** | Finds every search flagged **keep in sync** and reruns it headlessly every night at **02:30** (after the classification sync). | Runs on a timer; no manual start. |

## Prerequisites

To import, enable and configure workflows you need a Collibra account with one of:

- the **Sysadmin** global role, or
- a global role that includes the **Workflow Administration** global permission.

(The same permission is needed to set the Base URL and Bearer token in Step 4.)

---

## Step 1 — Import the six workflows

Do this for **each** of the six ZIP files:

1. Open **Settings**: click the **Products** icon (☰), then the **cogwheel** (⚙️).
2. Go to **Workflows → Definitions**.
3. Click **Upload a file** (or drag-and-drop the ZIP onto the page).
4. Wait for the progress bar to finish.

The order of import does not matter. Importing all six first is fine.

> Re-importing a workflow with the same process ID **replaces** the existing one
> in place — see [Updating the workflows](#updating-the-workflows) for what that
> preserves.

## Step 2 — Enable the workflows

Newly imported workflows are **disabled** by default, and a disabled workflow's
overview page renders blank. Enable each one:

1. In **Workflows → Definitions**, find the workflow's row.
2. Click the **play** icon (▶) at the end of the row.

Repeat for all six workflows.

## Step 3 — Run "Configure DataXray Workflows" once

This single run creates everything the other workflows depend on, including the
two access roles and the run permissions.

1. Click the **+ Create** button at the top of Collibra.
2. Choose **Configure DataXray Workflows** and confirm.
3. When it finishes, a results task appears in your inbox with a summary of what
   was created, configured and skipped. Open it, review, and click **Send** to
   close it.

It is safe to run again at any time — anything that already exists is left
untouched.

> **If you run this before all six workflows are imported**, it will report the
> missing ones as *"not deployed yet"* and skip setting their run permissions.
> Just import the rest (Steps 1–2) and **run Configure again**.

After this step you will have two new global roles:

- **DataXray Admin**
- **DataXray User**

## Step 4 — Set the DataXray connection (Base URL + Bearer token)

> **Cloud + Edge edition?** This step is different: there is no Bearer token,
> and the workflows need the name of an Edge HTTP connection instead. Follow
> Steps 1 and 4 of [deployment-guide-edge.md](deployment-guide-edge.md).

Four of the workflows — **Search DataXray**, **Rerun DataXray Search**,
**Sync DataXray Classifications**, and **Sync DataXray Classification
(Nightly)** — need to know your DataXray instance's address and an
authentication token. These are hidden configuration variables; they are never
shown to people who *run* the workflow and can only be set by an admin. (The
**Configure** workflow and **Sync DataXray Files (Nightly)** have no such
settings — the nightly file sync only starts reruns, which carry their own.)

For **each** of those four workflows:

1. Go to **Settings → Workflows → Definitions** and select the workflow.
2. Find the **Variables** section and click the **edit** icon.
3. Set:
   - **DataXray Base URL** — the base URL of your DataXray instance.
   - **DataXray Auth Token (Bearer)** — a valid Bearer token.
4. Save.

Until a real value is supplied, each field shows a placeholder such as
`<paste DataXray base URL here>`; a workflow run will stop with a clear error
if it is still unset.

> **Rerun DataXray Search** also has a hidden **Headless** variable. Leave it
> at `false` — the nightly file sync sets it per run when it starts reruns
> automatically.

## Step 5 — Assign the access roles to users

The Configure workflow created the roles but does not decide *who* belongs to
them — you assign that per environment. You do **not** need to create or use
groups first; a global role can be assigned directly to a user (or to a group,
if you prefer to manage many users at once).

1. Go to **Settings → Roles and responsibilities → Global roles**.
2. Select a role and add members (users and/or groups):

| Role | Give it to | What it grants |
|---|---|---|
| **DataXray User** | Everyday users who should run searches and syncs. | Permission to **run** Search DataXray, Rerun DataXray Search and Sync DataXray Classifications. |
| **DataXray Admin** | The people who manage the DataXray integration. | Permission to run everything **and** to edit the Base URL / Bearer token. ⚠️ This role carries the **Workflow Administration** permission, which is admin over *all* workflows on the instance — assign it deliberately. |

Sysadmins (and anyone with Workflow Administration) can already run and configure
everything without being added to these roles.

> **Treat the Classifications and Files domains as sync-owned.** By default,
> Collibra users cannot delete assets at all — deletion requires an explicitly
> granted responsibility whose role includes asset removal (or Sysadmin). Keep
> it that way: don't grant delete-capable responsibilities (Steward, Owner and
> similar) on these two domains, and let the syncs manage their content —
> deleted classifications are **retired** and files no search returns get the
> status **Not in any search**, preserving history.

## Who can see what

- The workflows refer to the **Collibra to DataXray Integration** community
  and its three domains only by their fixed IDs, and Configure never renames
  them. Renaming the community or the domains in Collibra is safe.
- **Keep the DataXray Classifications domain readable by everyone** who
  works with file or query assets. A user who cannot view a classification
  asset cannot see relations to it either (Collibra: "View permissions also
  impact asset relations"), so classifications moved into a restricted
  community silently disappear from file assets. Do not move classification
  assets out of this domain: the classification sync only looks for them
  there.
- **File assets and saved searches can be private.** Set view permissions on
  the **DataXray Files** and **DataXray Sync Filter** domains (view
  permissions can be set per domain, not only per community); no separate
  community is needed. Restrict the queries domain as well, because a query's
  *returns* links list file names. A user who cannot view a file asset does
  not see it through a query asset's relations either.
- **Moving file assets to another community is safe.** They keep syncing
  wherever they are: the integration's background tasks run as Collibra's
  **Workflow** user, which has no permission restrictions, and the check for
  an existing asset covers the whole instance, so no duplicate is created in
  the Files domain. See
  [Can file assets be moved to another community?](file-asset-lifecycle.md#can-file-assets-be-moved-to-another-community).

---

## Using the workflows

- **Search DataXray** and **Sync DataXray Classifications** — run from the
  **+ Create** menu. Visible to users holding **DataXray User** or
  **DataXray Admin** (and to admins).
- **Importing search results as assets** — after a search, the results task
  offers three independent options:
  - **Import now: create assets for all N matching files** (up to
    **10,000** files per search, and an instance-wide limit of **25,000**
    DataXray File assets, wherever they live; a warning appears above
    5,000). A search matching more than 10,000 files cannot be imported or
    kept in sync: refine it first.
  - **Keep in sync nightly** tags the query asset `dataxray-keep-in-sync` for
    the nightly file sync. It does not require importing now.
  - **Create assets for new files found on later reruns** (ticked by default).
    Unticked, the query asset is tagged `dataxray-no-auto-import`: reruns
    refresh the file assets already in Collibra but create none for files that
    only start matching later.

  The tags are set as soon as you click **Send**, and you can add or remove
  either one on the query asset at any time.
- **Rerun DataXray Search** — open a saved **Unstructured Data Query** asset
  and start the workflow from its page. The saved criteria are rebuilt from the
  query's linked classifications (so DataXray renames are picked up
  automatically); if a criterion was deleted in DataXray, the rerun stops and
  names it rather than silently broadening the search.
- **Sync DataXray Classification (Nightly)** — runs automatically every night
  at **02:00**; there is nothing to start by hand.
- **Sync DataXray Files (Nightly)** — runs automatically every night at
  **02:30**, rerunning every search tagged `dataxray-keep-in-sync`. Remove that
  tag from a query asset to stop syncing it; add it to resume.
- **Never delete** — classification assets that disappear from DataXray are
  set to status **Obsolete**. A file asset that **no** saved search returns
  any more gets the status **Not in any search**, and goes back to
  **Candidate** if a search matches it again. Both keep their comments,
  attachments and workflow history. A status a person set on a file asset
  (Accepted, Under Review and so on) is never changed.
- **Links are kept as history** — when a search stops returning a file, its
  *returns* link becomes *formerly returns*; when DataXray no longer finds a
  classification on a file, the file's *groups* link becomes *formerly
  groups*. Both are swapped back if they apply again. Links that people create
  are never removed or changed. The full file-asset lifecycle — creation, the
  25,000 cap, sync, orphaning, deletion and what to do at the limit — is
  explained in [file-asset-lifecycle.md](file-asset-lifecycle.md).
- **Deleting a saved query** — deleting an Unstructured Data Query asset does
  **not** delete its imported file assets. Files that only that query returned
  are picked up by the nightly file sync's orphan sweep and set to **Not in any
  search** the following night; files other queries also return are
  unaffected. Prefer
  removing the `dataxray-keep-in-sync` tag (to stop syncing) or retiring the
  query asset over deleting it — deletion also discards its attached results
  CSV and history.

## Verifying the setup

- In **Workflows → Definitions**, all six workflows show as **enabled**.
- **Search DataXray** and **Sync DataXray Classifications** appear in the
  **+ Create** menu for a user who has the **DataXray User** role.
- **Rerun DataXray Search** appears on the page of an **Unstructured Data
  Query** asset (create one via Search DataXray first).
- A test run of **Search DataXray** completes without a "connection not set"
  error (i.e. Step 4 was done), and its results task offers the import controls.

## Which version is installed?

Every workflow states the release it came from, as *DataXray workflows vX.Y.Z (onprem)* or *(cloud-edge)*:

- at the end of its description on its overview page (Settings → Workflows → Definitions → select the workflow), also returned by the REST API (`GET /rest/2.0/workflowDefinitions/<id>`);
- in small print at the bottom of its result, summary and failure tasks;
- in the first `dgc.log` line of every run, and on the log line of any request that finally failed.

Check all six after an upgrade: a workflow that was not re-imported still shows its old version. (Workflows imported before v2.3.0 show no version.)

## Updating the workflows

To update a workflow later, simply **re-import** its new ZIP (Steps 1). Because
Collibra replaces a workflow with the same process ID in place:

- the **Base URL and Bearer token you set are preserved**, and
- the **run permissions are preserved**.

So a normal upgrade needs no reconfiguration. The exception is an upgrade
that adds to the operating model, such as v2.4 below.

### Upgrading from Data X-Ray to DataXray names

Releases before the DataXray rename call the product **Data X-Ray** and the queries domain **Data X-Ray Custom
Queries**. After re-importing the workflows, run **Configure DataXray Workflows** once: it renames the
community, the three domains (the queries domain becomes **DataXray Sync Filter**), the **DataXray File** asset type,
the **DataXray ID**, **DataXray Index ID** and **DataXray Query** attribute types, the **DataXray Admin** and
**DataXray User** roles and the six workflow definitions. It only renames an element that still has its old default
name: one you renamed yourself keeps your name. The workflows refer to all of these by ID, so nothing else changes:
settings, run permissions, tags (`dataxray-keep-in-sync`, `dataxray-no-auto-import`) and the Edge connection name
(`data-xray` by default) stay as they are.

### Upgrading to v2.4

Re-import all six workflows (in place; settings and run permissions are
kept), then **run Configure DataXray Workflows once** as an admin (Step 3).
It creates the **Not in any search** status and the **formerly returns** and
**formerly groups** relation types, and adds them to the asset type
assignments. Until it has run, **Rerun DataXray Search** refuses to start
with a message saying so, and the nightly file sync skips its orphan sweep
(logged in `dgc.log`).

If Configure reports that it left an assignment alone because it has
hand-tuned rules, the new relations still work but do not show on that asset
type's page; add them in **Settings > Operating model > Asset types >
Assignments**. The first nightly file sync after the upgrade moves file assets
that earlier releases set to **Obsolete** because no search returned them to
**Not in any search**.

The one exception is if you **delete** a workflow and then import it again — that
resets both its configuration variables *and* its run permissions to defaults. In
that case, re-enter the token/URL (Step 4) and **run Configure once more** (Step
3) to restore the run permissions.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Workflow's overview page is blank. | The workflow is disabled — enable it (Step 2). |
| A run stops with "DataXray … is not set" / "misconfigured". | The Base URL or Bearer token is still the placeholder — set it (Step 4). |
| Configure's results list a workflow as **"not deployed yet"**. | That workflow wasn't imported when Configure ran — import it (Steps 1–2) and run Configure again (Step 3). |
| A user can't see Search/Sync in **+ Create**. | They don't hold **DataXray User** or **DataXray Admin** — assign the role (Step 5). |
| A user can't edit the Base URL / token. | Editing requires **DataXray Admin** (or Sysadmin / Workflow Administration). |
| The import controls don't appear on the search results task. | Either the search returned 0 results, matched more than 10,000 files, or the projected number of DataXray File assets (wherever they live) exceeds the 25,000 cap (the task shows the reason). |
| A rerun stops with "This instance is missing the status 'Not in any search' …". | The workflows were upgraded to v2.4 but Configure has not been re-run — run it once (Step 3). |
| *formerly returns* or *formerly groups* links don't show on an asset page. | Configure left that asset type's assignment alone because it has hand-tuned rules (its results list it). Add the relation types in Settings > Operating model > Asset types > Assignments. |
| A search is refused with "… was deleted in DataXray (its Collibra asset is retired)". | A classification picked in the form was deleted in DataXray; the sync marked its Collibra asset Obsolete, but the pickers still offer it. Remove it from the search. |
| A rerun fails with "criterion no longer exists". | A classification used by the saved search was deleted in DataXray. Recreate it there and run the classification sync, or create a new search. |
| Nightly file sync did nothing. | No query assets carry the `dataxray-keep-in-sync` tag, or **Rerun DataXray Search** isn't imported/enabled — check `dgc.log` for the run summary. |
