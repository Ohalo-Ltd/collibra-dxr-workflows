# Data X-Ray Workflows — Deployment & Configuration Guide

This guide explains how to deploy and configure the Data X-Ray workflows in
Collibra entirely through the Collibra user interface. You will be provided with
**six Workflow Designer ZIP files**, one per workflow. No command-line tools or
scripts are required — everything below is done from within Collibra.

## The six workflows

| Workflow | What it does | How it runs |
|---|---|---|
| **Configure Data X-Ray Workflows** | One-time setup. Creates the community, domains, asset types, attribute types, relation type and assignments the other workflows rely on, creates the two access-control roles, and sets who is allowed to run each workflow. Idempotent — safe to run again. | From the **+ Create** menu. Run once after importing. |
| **Search Data X-Ray** | Builds a Data X-Ray search query from Collibra classifications (every selected label, extractor and annotator must match — criteria are always AND-ed), previews the matching files, and offers to **import all results as file assets** (with an optional nightly keep-in-sync flag). | From the **+ Create** menu, on demand. |
| **Rerun Data X-Ray Search** | Reruns a saved search from its query asset and re-syncs its imported file assets: new files imported, changed files updated, files that no longer match **retired** (never deleted). | From an **Unstructured Data Query** asset's page; also run headlessly by the nightly file sync. |
| **Sync Data X-Ray Classifications** | Syncs classifications from Data X-Ray into Collibra, on demand. Classifications that disappear from Data X-Ray are **retired**, not deleted. | From the **+ Create** menu, on demand. |
| **Sync Data X-Ray Classification (Nightly)** | The same classification sync, run automatically every night at **02:00** (server time). | Runs on a timer; no manual start. |
| **Sync Data X-Ray Files (Nightly)** | Finds every search flagged **keep in sync** and reruns it headlessly every night at **02:30** (after the classification sync). | Runs on a timer; no manual start. |

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

## Step 3 — Run "Configure Data X-Ray Workflows" once

This single run creates everything the other workflows depend on, including the
two access roles and the run permissions.

1. Click the **+ Create** button at the top of Collibra.
2. Choose **Configure Data X-Ray Workflows** and confirm.
3. When it finishes, a results task appears in your inbox with a summary of what
   was created, configured and skipped. Open it, review, and click **Send** to
   close it.

It is safe to run again at any time — anything that already exists is left
untouched.

> **If you run this before all six workflows are imported**, it will report the
> missing ones as *"not deployed yet"* and skip setting their run permissions.
> Just import the rest (Steps 1–2) and **run Configure again**.

After this step you will have two new global roles:

- **Data X-Ray Admin**
- **Data X-Ray User**

## Step 4 — Set the Data X-Ray connection (Base URL + Bearer token)

Four of the workflows — **Search Data X-Ray**, **Rerun Data X-Ray Search**,
**Sync Data X-Ray Classifications**, and **Sync Data X-Ray Classification
(Nightly)** — need to know your Data X-Ray instance's address and an
authentication token. These are hidden configuration variables; they are never
shown to people who *run* the workflow and can only be set by an admin. (The
**Configure** workflow and **Sync Data X-Ray Files (Nightly)** have no such
settings — the nightly file sync only starts reruns, which carry their own.)

For **each** of those four workflows:

1. Go to **Settings → Workflows → Definitions** and select the workflow.
2. Find the **Variables** section and click the **edit** icon.
3. Set:
   - **Data X-Ray Base URL** — the base URL of your Data X-Ray instance.
   - **Data X-Ray Auth Token (Bearer)** — a valid Bearer token.
4. Save.

Until a real value is supplied, each field shows a placeholder such as
`<paste Data X-Ray base URL here>`; a workflow run will stop with a clear error
if it is still unset.

> **Rerun Data X-Ray Search** also has a hidden **Headless** variable. Leave it
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
| **Data X-Ray User** | Everyday users who should run searches and syncs. | Permission to **run** Search Data X-Ray, Rerun Data X-Ray Search and Sync Data X-Ray Classifications. |
| **Data X-Ray Admin** | The people who manage the Data X-Ray integration. | Permission to run everything **and** to edit the Base URL / Bearer token. ⚠️ This role carries the **Workflow Administration** permission, which is admin over *all* workflows on the instance — assign it deliberately. |

Sysadmins (and anyone with Workflow Administration) can already run and configure
everything without being added to these roles.

> **Treat the Classifications and Files domains as sync-owned.** By default,
> Collibra users cannot delete assets at all — deletion requires an explicitly
> granted responsibility whose role includes asset removal (or Sysadmin). Keep
> it that way: don't grant delete-capable responsibilities (Steward, Owner and
> similar) on these two domains, and let the syncs manage their content —
> deleted classifications and files are **retired**, preserving history.

---

## Using the workflows

- **Search Data X-Ray** and **Sync Data X-Ray Classifications** — run from the
  **+ Create** menu. Visible to users holding **Data X-Ray User** or
  **Data X-Ray Admin** (and to admins).
- **Importing search results as assets** — after a search, the results task
  offers **Import all N matching files as assets** (up to an instance-wide
  limit of **25,000** file assets in the **Data X-Ray Files** domain; a warning
  appears above 10,000). Ticking **Keep in sync nightly** flags the search for
  the nightly file sync.
- **Rerun Data X-Ray Search** — open a saved **Unstructured Data Query** asset
  and start the workflow from its page. The saved criteria are rebuilt from the
  query's linked classifications (so Data X-Ray renames are picked up
  automatically); if a criterion was deleted in Data X-Ray, the rerun stops and
  names it rather than silently broadening the search.
- **Sync Data X-Ray Classification (Nightly)** — runs automatically every night
  at **02:00**; there is nothing to start by hand.
- **Sync Data X-Ray Files (Nightly)** — runs automatically every night at
  **02:30**, rerunning every search tagged `dataxray-keep-in-sync`. Remove that
  tag from a query asset to stop syncing it; add it to resume.
- **Retire, never delete** — file assets (and classification assets) that
  disappear from Data X-Ray are set to status **Obsolete**, keeping their
  comments, attachments and workflow history. A file asset is only retired once
  **no** saved search returns it any more, and is reactivated automatically if
  it reappears.
- **Deleting a saved query** — deleting an Unstructured Data Query asset does
  **not** delete its imported file assets. Files that only that query returned
  are picked up by the nightly file sync's orphan sweep and **retired** the
  following night; files other queries also return are unaffected. Prefer
  removing the `dataxray-keep-in-sync` tag (to stop syncing) or retiring the
  query asset over deleting it — deletion also discards its attached results
  CSV and history.

## Verifying the setup

- In **Workflows → Definitions**, all six workflows show as **enabled**.
- **Search Data X-Ray** and **Sync Data X-Ray Classifications** appear in the
  **+ Create** menu for a user who has the **Data X-Ray User** role.
- **Rerun Data X-Ray Search** appears on the page of an **Unstructured Data
  Query** asset (create one via Search Data X-Ray first).
- A test run of **Search Data X-Ray** completes without a "connection not set"
  error (i.e. Step 4 was done), and its results task offers the import controls.

## Updating the workflows

To update a workflow later, simply **re-import** its new ZIP (Steps 1). Because
Collibra replaces a workflow with the same process ID in place:

- the **Base URL and Bearer token you set are preserved**, and
- the **run permissions are preserved**.

So a normal upgrade needs no reconfiguration.

The one exception is if you **delete** a workflow and then import it again — that
resets both its configuration variables *and* its run permissions to defaults. In
that case, re-enter the token/URL (Step 4) and **run Configure once more** (Step
3) to restore the run permissions.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Workflow's overview page is blank. | The workflow is disabled — enable it (Step 2). |
| A run stops with "Data X-Ray … is not set" / "misconfigured". | The Base URL or Bearer token is still the placeholder — set it (Step 4). |
| Configure's results list a workflow as **"not deployed yet"**. | That workflow wasn't imported when Configure ran — import it (Steps 1–2) and run Configure again (Step 3). |
| A user can't see Search/Sync in **+ Create**. | They don't hold **Data X-Ray User** or **Data X-Ray Admin** — assign the role (Step 5). |
| A user can't edit the Base URL / token. | Editing requires **Data X-Ray Admin** (or Sysadmin / Workflow Administration). |
| The import controls don't appear on the search results task. | Either the search returned 0 results, or the projected **Data X-Ray Files** population exceeds the 25,000 cap (the task shows the reason). |
| A rerun fails with "criterion no longer exists". | A classification used by the saved search was deleted in Data X-Ray. Recreate it there and run the classification sync, or create a new search. |
| Nightly file sync did nothing. | No query assets carry the `dataxray-keep-in-sync` tag, or **Rerun Data X-Ray Search** isn't imported/enabled — check `dgc.log` for the run summary. |
