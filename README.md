# collibra-dxr-workflows

The **Data X-Ray** [Collibra](https://www.collibra.com/) workflows — six Groovy/BPMN workflows released together as one customer bundle.

Collibra workflows are written in Groovy and run on a [Flowable](https://www.flowable.com/) BPMN engine embedded in the platform. This repo keeps each workflow's BPMN, Groovy scripts, and JSON forms under version control; the [collibra-workflower](https://github.com/Ohalo-Ltd/collibra-workflower) harness builds them into Workflow Designer ZIPs (identical to what the Collibra UI exports) and this repo's release Action ships them as a single bundle attached to each GitHub release.

There are two audiences:

- **Deploying to a Collibra instance?** You only need the release bundle and [`docs/deployment-guide.md`](docs/deployment-guide.md) — everything is done through the Collibra UI, no command line required.
- **Changing the workflows?** See [Developing](#developing) below.

## The workflows

Six workflows make up the Data X-Ray integration. Most are **global** (started from the Collibra **+ Create** menu); the rerun workflow is started from a saved query asset's page, and the two nightly jobs run on timers.

| Workflow | Key | What it does |
|---|---|---|
| **Configure Data X-Ray Workflows** | `configureDataXrayWorkflows` | One-time admin setup. Idempotently creates the Data X-Ray operating model (community, domains, asset types, attribute types, the returns/returned-by relation type, assignments) that the other workflows reference by fixed ID, creates the two access-control roles, and stamps who is allowed to run each workflow. Safe to re-run. |
| **Search Data X-Ray** | `searchDataXray` | Builds a Data X-Ray search query from the labels, extractors, and annotators you choose, previews the matching files, and offers to **import all results as file assets** (all-or-nothing, gated by an instance-wide 25k cap) with an optional **keep in sync nightly** flag. Run on demand. |
| **Rerun Data X-Ray Search** | `rerunDataXraySearch` | Started from a saved **Unstructured Data Query** asset (ASSET-scoped). Rebuilds the query from the asset's classification relations + stored filter, reruns it, and re-syncs the imported file assets — new files created, changed files updated, files no longer returned by **any** query **retired** (status Obsolete, never deleted). Also run headlessly by the nightly file sync. |
| **Sync Data X-Ray Classifications** | `syncDataXrayClassifications` | Syncs the Data X-Ray classification catalog into Collibra and shows what changed. Orphans are **retired**, not deleted; reappearing classifications are reactivated. Run on demand. |
| **Sync Data X-Ray Classification (Nightly)** | `syncDataXrayClassificationsNightly` | The same classification sync, run automatically every night at **02:00** (server time). No manual start. |
| **Sync Data X-Ray Files (Nightly)** | `syncDataXrayFilesNightly` | Finds every query asset tagged `dataxray-keep-in-sync` and starts one headless rerun per query, every night at **02:30** (after the classification sync). No manual start. |

Each lives in its own directory under [`workflows/`](workflows/) — `app.json` (metadata), `workflow.bpmn` (process definition), `scripts/*.groovy` (logic), and `forms/*.form` (user input).

## Access control

Two custom **global roles** gate the workflows; both are created (idempotently, with fixed UUIDs) by **Configure Data X-Ray Workflows**:

| Role | Purpose |
|---|---|
| **Data X-Ray Admin** | Holds the `WORKFLOW_ADMINISTRATION` permission so members (alongside Sysadmins) can set each workflow's Base URL and Bearer token. ⚠️ That permission is global — it confers admin over *all* workflows on the instance; Collibra has no per-workflow admin permission. |
| **Data X-Ray User** | Bare membership role with no permissions. Only gates who may *run* the search/sync workflows. |

Who may run each workflow is set at runtime by **Configure Data X-Ray Workflows** (search/sync/rerun → User + Admin; nightlies + configure → Admin). The BPMN's `candidateStarterGroups` is inert in Collibra, so access can only be asserted by re-running configure — see [CLAUDE.md](CLAUDE.md) for the full mechanics.

## Releasing

Publishing a GitHub release produces the customer-ready deliverable automatically. The [`Bundle Data X-Ray Workflow ZIPs`](.github/workflows/release-bundle.yml) Action checks out the harness at the version pinned in [`pack.json`](pack.json) (`harnessRef`), runs `deploy.py bundle`, and attaches **`collibra-data-xray-workflows-<tag>.zip`** to the release. No Collibra credentials are involved — the build is entirely offline.

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
acceptance-criteria.md
```

> To build the bundle without cutting a release, run the Action manually from the **Actions** tab (`workflow_dispatch`) — the bundle is uploaded as a run artifact instead. The Action needs the `HARNESS_READ_TOKEN` repository secret (read access to the internal harness repo).

## Developing

This repo is a *workflow pack*: it holds only workflow sources and their docs. The build/deploy tooling, Groovy IDE stubs and Collibra reference docs live in the `collibra-workflower` harness, which is currently **Ohalo-internal** and mounts this repo as a git submodule at `packs/dxr-workflows`. External contributors: see [CONTRIBUTING.md](CONTRIBUTING.md). Ohalo developers work from the harness:

```bash
git clone --recurse-submodules git@github.com:Ohalo-Ltd/collibra-workflower.git
cd collibra-workflower
pip install -r requirements.txt && cp .env.example .env   # fill in Collibra credentials

python deploy.py list                                    # packs and workflows
python deploy.py search-data-xray --dry-run              # preview a ZIP
python deploy.py search-data-xray --enable               # deploy to your dev instance
python deploy.py bundle --pack packs/dxr-workflows       # build the release bundle locally
```

Commit and push pack changes from inside `packs/dxr-workflows` (it is its own git repo), then commit the updated submodule pointer in the harness. Deployed definition UUIDs are recorded per environment in [`workflow-registry.json`](workflow-registry.json).

Adding a workflow to the bundle = add its directory under `workflows/` **and** its name to `pack.json`'s `workflows` list. See the harness README for the workflow directory layout and Collibra gotchas, and [CLAUDE.md](CLAUDE.md) here for the Data X-Ray specifics (access control, file import model, DXR API facts).

## License

Source-available under the [Ohalo Source-Available License](LICENSE) — free to inspect and to deploy for organisations licensed for Data X-Ray; not open source.
