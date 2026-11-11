"""Diagnostic contract tests; mocked help output is not a reconstruction fixture."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import pytest
from spacefit import native_runtime

DOCTOR = Path(__file__).resolve().parents[2] / "backend/scripts/doctor.py"
spec = importlib.util.spec_from_file_location("qa_doctor", DOCTOR)
doctor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(doctor)


class FixtureSettings:
    def executables(self):
        return {"colmap": "fixture-colmap", "blender": "fixture-blender", "TextureMesh": "fixture-mvs"}


def test_help_exit_one_is_only_accepted_for_openmvs(monkeypatch, tmp_path):
    def fake_run(command, **kwargs):
        name = command[0]
        return subprocess.CompletedProcess(command, 0 if name == "fixture-blender" else 1,
            b"Blender 5.0 fixture" if name == "fixture-blender" else b"OpenMVS 2.4 Available options: fixture", b"")
    monkeypatch.setattr(native_runtime.subprocess, "run", fake_run)
    results = {row["tool"]: row for row in doctor.diagnose_tools(FixtureSettings(), tmp_path, 5)}
    assert results["TextureMesh"]["status"] == results["blender"]["status"] == "ready"
    assert results["colmap"]["status"] == "error"


def test_loader_error_and_timeout_are_diagnostic_failures(monkeypatch, tmp_path):
    def fake_run(command, **kwargs):
        if command[0] == "fixture-colmap":
            raise subprocess.TimeoutExpired(command, 5)
        return subprocess.CompletedProcess(command, 0xC0E90002, b"", b"")
    monkeypatch.setattr(native_runtime.subprocess, "run", fake_run)
    results = doctor.diagnose_tools(FixtureSettings(), tmp_path, 5)
    assert all(row["status"] == "error" for row in results)
    assert "timed out" in results[0]["detail"]
    assert results[1]["hex_exit"] == "0xc0e90002"


def test_real_cli_missing_tools_exits_nonzero_without_loading_secrets(tmp_path):
    token = "fake-doctor-token-for-tests"
    env = dict(os.environ, SPACEFIT_TOKEN=token, SPACEFIT_OPENMVS_RUNTIME="native", COLMAP_BIN=str(tmp_path / "absent-colmap.exe"),
        BLENDER_BIN=str(tmp_path / "absent-blender.exe"), OPENMVS_BIN_DIR=str(tmp_path / "absent-mvs"))
    result = subprocess.run([sys.executable, "-B", str(DOCTOR), "--no-config",
        "--diagnostics-dir", str(tmp_path / "logs")], env=env, capture_output=True, text=True, timeout=15)
    assert result.returncode == 1
    assert token not in result.stdout + result.stderr
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    assert len(rows) == 7 and all(row["status"] == "missing" for row in rows)
    assert not (tmp_path / "logs").exists()


@pytest.mark.parametrize("runtime", [None, "native"])
def test_doctor_selects_verified_colmap_config_but_honors_explicit_native(monkeypatch, tmp_path, runtime):
    monkeypatch.setattr(doctor, "ROOT", tmp_path)
    # Track even an initially absent key: load_config writes os.environ directly,
    # while delenv(raising=False) cannot undo a key that did not exist before.
    monkeypatch.setenv("SPACEFIT_COLMAP_RUNTIME", runtime or "")
    monkeypatch.delenv("SPACEFIT_OPENMVS_RUNTIME", raising=False)
    # No real .env is read. Existence fixture only; Settings validates its contents separately.
    (tmp_path / "native-colmap-docker.json").write_text("{}", encoding="utf-8")
    doctor.load_config()
    assert os.environ["SPACEFIT_COLMAP_RUNTIME"] == (runtime or "docker")
    assert "SPACEFIT_OPENMVS_RUNTIME" not in os.environ
