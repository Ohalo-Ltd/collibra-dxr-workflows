# collibra-dxr-workflows

The **DataXray** [Collibra](https://www.collibra.com/) workflows — six Groovy/BPMN workflows released together, in **two editions**: an **on-prem** bundle whose scripts call DataXray directly over HTTPS, and a **Collibra Cloud + Edge** bundle that reaches an on-premises DataXray through a Collibra Edge site's HTTP connection.

Collibra workflows are written in Groovy and run on a [Flowable](https://www.flowable.com/) BPMN engine embedded in the platform. This repo keeps each workflow's BPMN, Groovy scripts, and JSON forms under version control; the [collibra-workflower](https://github.com/Ohalo-Ltd/collibra-workflower) harness builds them into Workflow Designer ZIPs (identical to what the Collibra UI exports) and this repo's release Action ships them as a single bundle attached to each GitHub release.

There are two audiences:

- **Deploying to a Collibra instance?** You only need the release bundle and [`docs/deployment-guide.md`](docs/deployment-guide.md) (on-prem edition) or [`docs/deployment-guide-edge.md`](docs/deployment-guide-edge.md) (Collibra Cloud + Edge edition) — everything is done through the Collibra UI, no command line required. [`docs/file-asset-lifecycle.md`](docs/file-asset-lifecycle.md) explains how imported file assets are created, capped, kept in sync, marked *Not in any search* and (never) deleted. [`docs/standalone-classification-sync.md`](docs/standalone-classification-sync.md) covers the optional Python script that runs the classification sync outside Collibra.
- **Changing the workflows?** See [Developing](#developing) below.

## The workflows

Six workflows make up the DataXray integration. Most are **global** (started from the Collibra **+ Create** menu); the rerun workflow is started from a saved query asset's page, and the two nightly jobs run on timers.

| Workflow | Key | What it does |
|---|---|---|
| **Configure DataXray Workflows** | `configureDataXrayWorkflows` | One-time admin setup. Idempotently creates the DataXray operating model (community, domains, asset types, attribute types, the returns, formerly returns and formerly groups relation types, the *Not in any search* status, assignments) that the other workflows reference by fixed ID, creates the two access-control roles, and stamps who is allowed to run each workflow. Its one setting, *Sync extractor values to file assets* (default `false`), adds or removes the optional *DataXray Extracted Values* attribute. Safe to re-run. |
| **Search DataXray** | `searchDataXray` | Builds a DataXray search query from the labels, extractors, and annotators you choose (all criteria are AND-ed — a file must match every selected value; the optional annotated text may match in any of the annotators chosen for it), previews the matching files, and offers three independent options: **import all results as file assets** now (all-or-nothing, gated by an instance-wide 25k cap), **keep in sync nightly** (tag `dataxray-keep-in-sync`), and **create assets for new files on later reruns** (unticked: tag `dataxray-no-auto-import`). Run on demand. |
| **Rerun DataXray Search** | `rerunDataXraySearch` | Started from a saved **DataXray Sync Filter** asset (ASSET-scoped). Rebuilds the query from the asset's classification relations + stored filter, reruns it, and re-syncs the imported file assets — new files created (unless the query is tagged `dataxray-no-auto-import`), changed files updated, links that stop applying swapped to *formerly returns* / *formerly groups*, and files no longer returned by **any** query set to **Not in any search** (never deleted). A status a person set and links people created are never changed. Also run headlessly by the nightly file sync. |
| **Sync DataXray Classifications** | `syncDataXrayClassifications` | Syncs the DataXray classification catalog into Collibra and shows what changed. Orphans are **retired**, not deleted; reappearing classifications are reactivated. Run on demand. |
| **Sync DataXray Classification (Nightly)** | `syncDataXrayClassificationsNightly` | The same classification sync, run automatically every night at **02:00** (server time). No manual start. |
| **Sync DataXray Files (Nightly)** | `syncDataXrayFilesNightly` | Finds every query asset tagged `dataxray-keep-in-sync` and starts one headless rerun per query, every night at **02:30** (after the classification sync), then sets DataXray File assets no query returns to *Not in any search*. The Cloud + Edge edition then checks those files against DataXray: the ones DataXray no longer has (file or datasource deleted) get *Not in DataXray*, and the ones it still has get their attributes and classification links refreshed. No manual start. |

Each lives in its own directory under [`workflows/`](workflows/) — `app.json` (metadata), `workflow.bpmn.template` (process definition; a template, not an importable file), `scripts/*.groovy` (logic), and `forms/*.form` (user input).

## Access control

Two custom **global roles** gate the workflows; both are created (idempotently, with fixed UUIDs) by **Configure DataXray Workflows**:

| Role | Purpose |
|---|---|
| **DataXray Admin** | Holds the `WORKFLOW_ADMINISTRATION` permission so members (alongside Sysadmins) can set each workflow's Base URL and Bearer token. ⚠️ That permission is global — it confers admin over *all* workflows on the instance; Collibra has no per-workflow admin permission. |
| **DataXray User** | Bare membership role with no permissions. Only gates who may *run* the search/sync workflows. |

Who may run each workflow is set at runtime by **Configure DataXray Workflows** (search/sync/rerun → User + Admin; nightlies + configure → Admin). The BPMN's `candidateStarterGroups` is inert in Collibra, so access can only be asserted by re-running configure — see [CLAUDE.md](CLAUDE.md) for the full mechanics.

## Releasing

Publishing a GitHub release produces the customer-ready deliverables automatically. The [`Bundle DataXray Workflow ZIPs`](.github/workflows/release-bundle.yml) Action checks out the harness at the version pinned in [`pack.json`](pack.json) (`harnessRef`), runs `deploy.py bundle`, and attaches **two bundles** to the release — one per variant declared in `pack.json`:

| Bundle | Edition |
|---|---|
| `collibra-data-xray-workflows-onprem-<tag>.zip` | Direct HTTPS from Collibra to DataXray (Bearer token on the workflows). |
| `collibra-data-xray-workflows-cloud-edge-<tag>.zip` | Collibra Cloud → Edge site → DataXray (credentials on the Edge HTTP connection; DataXray is paged through its internal search API). |

Both hold the same six workflows under the same process ids; a customer installs one of them. No Collibra credentials are involved — the build is entirely offline.

```bash
gh release create v1.2.0 --generate-notes
```

The bundle contains one Workflow Designer ZIP per workflow listed in `pack.json` plus everything under [`docs/`](docs/):

```
configure-data-xray-workflows.zip
search-data-xray.zip
rerun-data-xray-search.zip
sync-data-xray-classifications.zip
sync-data-xray-classifications-nightly.zip
sync-data-xray-files-nightly.zip
deployment-guide.md
file-asset-lifecycle.md
acceptance-criteria.md
images/…
```

> To build the bundle without cutting a release, run the Action manually from the **Actions** tab (`workflow_dispatch`) — the bundle is uploaded as a run artifact instead. The Action needs the `HARNESS_READ_TOKEN` repository secret (read access to the internal harness repo).

## Developing

This repo is a *workflow pack*: it holds only workflow sources and their docs. The build/deploy tooling, Groovy IDE stubs and Collibra reference docs live in the `collibra-workflower` harness, which is currently **Ohalo-internal** and mounts this repo as a git submodule at `packs/dxr-workflows`. External contributors: see [CONTRIBUTING.md](CONTRIBUTING.md). Ohalo developers work from the harness:

```bash
git clone --recurse-submodules git@github.com:Ohalo-Ltd/collibra-workflower.git
cd collibra-workflower
pip install -r requirements.txt && cp .env.example .env   # fill in Collibra credentials

python deploy.py list                                    # packs, variants and workflows
python deploy.py search-data-xray --dry-run              # preview a ZIP (default variant: onprem)
python deploy.py search-data-xray --enable               # deploy the on-prem edition to your dev instance
python deploy.py search-data-xray --variant edge         # deploy the Cloud + Edge edition instead (same process id)
python deploy.py bundle --pack packs/dxr-workflows       # build both release bundles locally
python tools/groovy_syntax_check.py packs/dxr-workflows  # compile every script of every variant (needs groovyc)
```

Shared Groovy lives in [`shared/`](shared/) and is pulled into scripts with `// {{include:name.groovy}}` at build time; the Edge edition adds `workflow.edge.bpmn.template` and `scripts/edge/` per workflow. See [CLAUDE.md](CLAUDE.md) for the two-edition design and the DataXray API contract each one relies on.

Commit and push pack changes from inside `packs/dxr-workflows` (it is its own git repo), then commit the updated submodule pointer in the harness. Deployed definition UUIDs are recorded per environment in [`workflow-registry.json`](workflow-registry.json).

Adding a workflow to the bundle = add its directory under `workflows/` **and** its name to `pack.json`'s `workflows` list. See the harness README for the workflow directory layout and Collibra gotchas, and [CLAUDE.md](CLAUDE.md) here for the DataXray specifics (access control, file import model, DXR API facts).

## License

Source-available under the [Ohalo Source-Available License](LICENSE) — free to inspect and to deploy for organisations licensed for DataXray; not open source.

## Attribution

These workflows were built by [Ohalo](https://ohalo.co/) in partnership with [Collibra](https://www.collibra.com/). The classification sync, and the approach of populating search queries as Collibra assets, are concepts developed by Vasiliki Nikolopoulou.
