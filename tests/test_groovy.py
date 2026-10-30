"""The offline Groovy tests, one pytest item per test file and edition.

All of an edition's Groovy tests run in one JVM (compiling the workflows'
scripts dominates the time); each pytest item then reports its file's results.
"""
from __future__ import annotations

import pytest

import groovy_suite

FILES = groovy_suite.test_files()
VARIANTS = groovy_suite.variants()


@pytest.fixture(scope="session")
def groovy_reports(groovy_bin, tmp_path_factory):
    cache: dict[str, dict] = {}

    def report(variant: str) -> dict:
        if variant not in cache:
            cache[variant] = groovy_suite.run(variant, tmp_path_factory.mktemp(f"groovy-{variant}"), echo=False)
        return cache[variant]
    return report


@pytest.mark.parametrize("variant", VARIANTS)
@pytest.mark.parametrize("test_file", FILES, ids=[f.stem for f in FILES])
def test_groovy_file(groovy_reports, variant, test_file):
    report = groovy_reports(variant)
    results = [r for r in report["results"] if r["file"] == test_file.name]
    assert results, f"{test_file.name} registered no tests for {variant}"
    failed = [r for r in results if r["status"] == "failed"]
    if failed:
        lines = [f"{len(failed)} of {len(results)} failed [{variant}]:"]
        for r in failed:
            lines.append(f"\n--- {r['name']}\n{r['message']}")
        pytest.fail("\n".join(lines), pytrace=False)
