#!/usr/bin/env python3
"""Build the offline Groovy test fixture for one edition and run the tests.

The fixture is what Collibra would run: every shared/ file with its includes
expanded and {{pack:…}} filled in (shared.groovy), and each workflow's .bpmn
and forms taken from the ZIP the harness builds for that edition. The Groovy
side (tests/groovy/run_tests.groovy) runs the *Test.groovy files against an
in-memory Collibra and DataXray.

    python tests/groovy_suite.py                       # both editions, every test
    python tests/groovy_suite.py --variant edge -k zero   # one edition, tests whose name contains 'zero'

Needs `groovy` on PATH (brew install groovy) and the harness: $HARNESS_ROOT,
else the harness this pack is mounted in (packs/<pack> → ../..).
"""
from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PACK = Path(__file__).resolve().parents[1]
TESTS = PACK / "tests"
GROOVY_DIR = TESTS / "groovy"


def harness_root() -> Path:
    candidates = [os.environ.get("HARNESS_ROOT"), PACK.parents[1], PACK / "harness"]
    for c in candidates:
        if c and (Path(c) / "lib" / "app_builder.py").exists():
            return Path(c).resolve()
    raise SystemExit("collibra-workflower harness not found: set HARNESS_ROOT")


sys.path.insert(0, str(harness_root()))
from lib.app_builder import apply_pack_placeholders, build_app, expand_file, pack_placeholder_values  # noqa: E402
from lib.pack import load_pack  # noqa: E402

VERSION = "test"


def variants() -> list[str]:
    return list(load_pack(PACK).variants)


def test_files() -> list[Path]:
    return sorted(GROOVY_DIR.glob("cases/**/*Test.groovy"))


def build_fixture(variant: str, out: Path) -> Path:
    pack = load_pack(PACK)
    v = pack.resolve_variant(variant)
    names = pack.workflows_for(v)
    values = pack_placeholder_values(pack.workflow_dir(names[0]), variant, VERSION)
    shared = expand_file(GROOVY_DIR / "shared_all.groovy")
    out.mkdir(parents=True, exist_ok=True)
    (out / "shared.groovy").write_text(apply_pack_placeholders(shared, values, "shared_all.groovy"), encoding="utf-8")
    for name in names:
        wf_out = out / "workflows" / name
        wf_out.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(build_app(pack.workflow_dir(name), variant, VERSION))) as zf:
            for member in zf.namelist():
                if member.endswith((".bpmn", ".form")):
                    (wf_out / member).write_bytes(zf.read(member))
    return out


def run(variant: str, workdir: Path, files: list[Path] | None = None, name_filter: str | None = None,
        echo: bool = True) -> dict:
    """Run the Groovy tests for one edition; returns the JSON report."""
    groovy = shutil.which("groovy")
    if not groovy:
        raise FileNotFoundError("groovy not found on PATH (brew install groovy)")
    fixture = build_fixture(variant, workdir / f"fixture-{variant}")
    report = workdir / f"report-{variant}.json"
    cmd = [groovy, "-cp", str(GROOVY_DIR), str(GROOVY_DIR / "run_tests.groovy"),
           "--fixture", str(fixture), "--variant", variant, "--report", str(report)]
    if name_filter:
        cmd += ["--filter", name_filter]
    cmd += [str(f) for f in (files or test_files())]
    proc = subprocess.run(cmd, cwd=PACK, capture_output=not echo, text=True)
    if not report.exists():
        output = "" if echo else (proc.stdout + proc.stderr)
        raise RuntimeError(f"Groovy test run ({variant}) wrote no report (exit {proc.returncode})\n{output[-6000:]}")
    data = json.loads(report.read_text(encoding="utf-8"))
    data["output"] = "" if echo else proc.stdout + proc.stderr
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--variant", choices=variants(), help="One edition only (default: all)")
    ap.add_argument("-k", dest="name_filter", help="Only tests whose file or name contains this text")
    ap.add_argument("--workdir", type=Path, help="Where to write the fixture and report (default: a temp dir)")
    ap.add_argument("files", nargs="*", type=Path, help="Test files (default: every tests/groovy/cases/**/*Test.groovy)")
    args = ap.parse_args()
    import tempfile
    workdir = args.workdir or Path(tempfile.mkdtemp(prefix="dxr-groovy-tests-"))
    failed = 0
    for variant in ([args.variant] if args.variant else variants()):
        report = run(variant, workdir, [f.resolve() for f in args.files] or None, args.name_filter)
        failed += sum(r["status"] == "failed" for r in report["results"])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
