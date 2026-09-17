# Standalone classification sync (Python script)

`sync_classifications_standalone.py` (shipped next to this guide in the release bundle; `tools/sync_classifications_standalone.py` in the repository) is a command-line equivalent of the **Sync Data X-Ray Classifications** workflow. It reads the classification catalogue straight from Data X-Ray and writes the same assets, attributes, tags and statuses the workflow writes, so the search, import, rerun and nightly workflows keep working against them.

Use it when the sync must run **outside Collibra**, for example on a machine inside the customer network that can reach both Data X-Ray and Collibra, and the workflow editions do not fit. In every other case prefer the workflow: it runs on a schedule inside Collibra, is gated by the Data X-Ray roles, shows a results screen and logs to the Collibra log. The script gives you none of that; scheduling and credential storage are yours.

## Prerequisites

1. **Import the workflow bundle and run `Configure Data X-Ray Workflows` once**, exactly as in the [deployment guide](deployment-guide.md). Configure creates the *Data X-Ray Classifications* domain, the Classification / Annotator / Extractor / Label asset types and the *Data X-Ray ID*, *Link*, *Search Link* and *Sub Type* attribute types, all with fixed ids. The script writes to those ids and does nothing useful on an instance where configure has not run.
2. A machine with **Python 3.10 or newer** and the `requests` package (`pip install requests`; `python-dotenv` is optional and lets you keep settings in a `.env` file).
3. A **Collibra user** allowed to create and edit assets in the *Data X-Ray Classifications* domain and to tag them.
4. A **Data X-Ray Bearer token** for a user who can see the classifications you want in Collibra, the same kind of token the on-prem workflow edition uses.

## Configure the script

Do **not** edit the Python file. Everything the technician provides is a setting, read from environment variables or from a `.env` file placed next to the script:

```
COLLIBRA_URL=https://<tenant>.collibra.com
COLLIBRA_USER=<collibra user>
COLLIBRA_PASSWORD=<password>
DXR_BASE_URL=https://<data-x-ray-host>
DXR_API_KEY=<Data X-Ray Bearer token>
```

`DXR_BASE_URL` is also the prefix of the *Link* and *Search Link* attributes; pass `--link-base https://...` if users should open Data X-Ray through a different address than the script uses.

The block marked **CANONICAL IDS, DO NOT EDIT** at the top of the script lists the domain, asset type, attribute type and status ids. They are the same on every Collibra instance because configure creates them, and each constant names the matching constant in the configure workflow's script. If an instance appears to have different ids, run the configure workflow; do not change the script.

## Run it

Always start with a dry run. It reads everything and writes nothing:

```
python sync_classifications_standalone.py --dry-run
```

Then run it for real:

```
python sync_classifications_standalone.py
```

For an instance running the **Collibra Cloud + Edge edition** of the search and rerun workflows, add `--stamp-index-ids`. That also writes the numeric *Data X-Ray Index ID* those workflows need, reading it from Data X-Ray's internal catalogue endpoints. The attribute type is created by configure from pack v2.1.0 onwards.

The last line is the summary, in the same terms as the workflow's results screen:

```
Sync complete: created=0 updated=69 retired=0 skipped=13 failed=0
```

`skipped` counts Data X-Ray data categories, which the workflow does not mirror either. The exit code is 0 when everything synced and 1 when any item failed or the catalogue came back empty, so a scheduler can alert on it. To run it nightly, a cron entry such as `0 2 * * * cd /opt/dxr-sync && python sync_classifications_standalone.py >> sync.log 2>&1` does the job; keep the `.env` file readable only by that account.

## What it does, and what it deliberately mirrors

- **Upsert by Data X-Ray ID.** Each asset is found through its *Data X-Ray ID* attribute, so a rename in Data X-Ray renames the asset rather than creating a duplicate. An existing same-named asset without the attribute is adopted once.
- **Colliding names** are suffixed with the subtype, for example `VAT Number (Named Entity)` and `VAT Number (Regular Expression)`.
- **Tag `dataxray-classification-sync`** on every asset it writes. Assets in the domain without that tag are never touched.
- **Retire, never delete.** Tagged assets that disappeared from Data X-Ray are set to *Obsolete*; they come back to *Candidate* if the classification reappears. A rerun of a saved search relies on this to refuse a query whose criterion was deleted. If Data X-Ray returns an empty catalogue nothing is retired.
- Description, Link, Search Link and Sub Type are replaced on every run; a value missing in Data X-Ray clears the attribute.

## Do not mix it with the nightly workflow

Run either the script or the *Sync Data X-Ray Classifications (nightly)* workflow, not both. They write the same assets and one of them is redundant.
