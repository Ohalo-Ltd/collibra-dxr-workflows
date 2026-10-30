"""Shared pytest setup for the pack's tests.

The harness (collibra-workflower) provides the build: $HARNESS_ROOT, else the
harness this pack is mounted in as a submodule (packs/<pack> → ../..), else a
checkout at ./harness (the pack's CI).
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import pytest

TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS))

import groovy_suite  # noqa: E402  (also puts the harness on sys.path)


def pytest_configure(config):
    config.addinivalue_line("markers", "e2e: live end-to-end test against a Collibra instance (needs DXR_E2E=1)")


@pytest.fixture(scope="session")
def groovy_bin() -> str:
    found = shutil.which("groovy")
    if not found:
        if os.environ.get("CI"):
            pytest.fail("groovy is required in CI (see .github/workflows/ci.yml)")
        pytest.skip("groovy not on PATH (brew install groovy)")
    return found
