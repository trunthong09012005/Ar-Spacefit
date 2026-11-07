"""Exercise the CI report gate with disposable JUnit files and real PowerShell."""
import os
from pathlib import Path
import subprocess

import pytest


pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows CI report gate")
SCRIPT = Path(__file__).resolve().parents[2] / "tools/Assert-JUnit.ps1"


@pytest.mark.parametrize("report,passes", [
    ('<testsuite tests="2" failures="0" errors="0" skipped="0"/>', True),
    ('<testsuite tests="2" failures="1" errors="0" skipped="0"/>', False),
    ('<testsuite tests="2" failures="0" errors="1" skipped="0"/>', False),
    ('<testsuite tests="2" failures="0" errors="0" skipped="1"/>', False),
    ('<testsuite tests="0" failures="0" errors="0" skipped="0"/>', False),
    ('<testsuites/>', False),
    ('not XML', False),
    (None, False),
])
def test_ci_rejects_missing_failed_skipped_or_empty_reports(tmp_path, report, passes):
    if report is not None:
        (tmp_path / "results.xml").write_text(report, encoding="utf-8")
    result = subprocess.run([
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        str(SCRIPT), "-Path", str(tmp_path / "*.xml"),
    ], capture_output=True, text=True, timeout=20)
    assert (result.returncode == 0) is passes, result.stdout + result.stderr
    if passes:
        assert "total=2 passed=2 failed=0 errors=0 skipped=0" in result.stdout
