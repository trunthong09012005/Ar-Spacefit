"""Regression for Windows runner: stderr warning != command failure, exit errors must propagate."""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import pytest

pytestmark = pytest.mark.skipif(os.name != "nt", reason="PowerShell Windows test runner")
SUPPORT = Path(__file__).resolve().parents[2] / "tools" / "Test-Support.ps1"


def run_ps(statement, env=None):
    return subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "$ErrorActionPreference='Stop'; . '" + str(SUPPORT).replace("'", "''") + "'; " + statement],
        capture_output=True, text=True, timeout=20, env=env)


def command(code, args=()):
    values = ["-c", code, *args]
    quoted = ",".join("'" + value.replace("'", "''") + "'" for value in values)
    return "Invoke-QACommand '" + sys.executable.replace("'", "''") + "' @(" + quoted + ") -TimeoutSeconds 5"


def test_stderr_warning_does_not_abort_successful_command():
    result = run_ps(command("import sys; print('ok'); print('warning',file=sys.stderr)"))
    assert result.returncode == 0, result.stderr
    assert "ok" in result.stdout and "warning" in result.stdout


def test_nonzero_exit_and_timeout_fail_runner():
    result = run_ps(command("raise SystemExit(7)"))
    assert result.returncode != 0 and "code 7" in result.stderr
    result = run_ps(command("import time; time.sleep(20)").replace("-TimeoutSeconds 5", "-TimeoutSeconds 1"))
    assert result.returncode != 0 and "timed out" in result.stderr


def test_windows_argument_quoting_roundtrips_spaces_quotes_and_slashes():
    args = ["path with spaces", 'embedded"quote', "trailing\\", "\\\"", ""]
    result = run_ps(command("import sys,json; print(json.dumps(sys.argv[1:]))", args))
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == args


@pytest.mark.parametrize("restricted_modules", [False, True])
@pytest.mark.parametrize("relative_path", [False, True])
def test_artifact_checksum_is_plain_text_for_logs(tmp_path, restricted_modules, relative_path):
    artifact = tmp_path / "QA checksum fixture.apk"
    contents = b"checksum fixture, not an Android application"
    artifact.write_bytes(contents)
    env = dict(os.environ)
    if restricted_modules:
        module_path = tmp_path / "empty-modules"
        module_path.mkdir()
        env["PSModulePath"] = str(module_path)
    argument = artifact.name if relative_path else str(artifact)
    statement = "Set-Location '" + str(tmp_path).replace("'", "''") + "'; " if relative_path else ""
    if restricted_modules:
        # Reproduce a runner where the optional hashing function is unavailable;
        # Windows PowerShell may otherwise append its default module paths.
        statement += "Remove-Item Function:\\Get-FileHash -ErrorAction SilentlyContinue; $PSModuleAutoLoadingPreference='None'; "
    result = run_ps(statement + "Write-QAFileHash '" + argument.replace("'", "''") + "'", env=env)
    assert result.returncode == 0, result.stderr
    assert "SHA256=" + hashlib.sha256(contents).hexdigest().upper() in result.stdout
    assert str(artifact) in result.stdout
    assert "FormatStartData" not in result.stdout


def test_missing_artifact_never_emits_checksum(tmp_path):
    result = run_ps("Write-QAFileHash '" + str(tmp_path / "missing.apk").replace("'", "''") + "'")
    assert result.returncode != 0
    assert "SHA256=" not in result.stdout


def test_gate_records_failures_and_skips_and_returns_nonzero(tmp_path):
    runner = SUPPORT.with_name("Test-All.ps1")
    result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(runner),
        "-SkipAndroid", "-PythonPath", str(tmp_path / "missing-python.exe"),
        "-BlenderBin", str(tmp_path / "missing-blender.exe"), "-RequireDevice"],
        capture_output=True, text=True, timeout=20)
    assert result.returncode == 1
    assert "Parameter set cannot be resolved" not in result.stdout
    report_path = Path(next(line.removeprefix("Reports: ") for line in result.stdout.splitlines() if line.startswith("Reports: ")))
    summary = json.loads((report_path / "summary.json").read_text(encoding="utf-8-sig"))
    states = {row["name"]: row["status"] for row in summary}
    assert states["backend-core"] == states["backend-http-blackbox"] == "FAIL"
    assert states["android-build-unit-lint"] == states["blender-integration"] == "SKIP"
    assert states["android-ui-blackbox"] == "FAIL"
