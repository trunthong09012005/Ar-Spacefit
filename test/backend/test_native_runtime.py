"""Cache/probe contracts. Mocked help is diagnostic-only, not reconstruction evidence."""
from concurrent.futures import ThreadPoolExecutor
import json
import subprocess
import sys
import threading
import time

import pytest
from fastapi.testclient import TestClient
from spacefit.api import create_app
from spacefit.config import Settings
from spacefit.native_runtime import NativeReadiness, TOOL_NAMES, probe_tool
from spacefit import native_runtime
from spacefit.pipeline import Pipeline
from spacefit.store import JobStore


class FixtureSettings:
    def __init__(self, prefix="fixture"):
        self.paths = {name: f"{prefix}-{name}" for name in TOOL_NAMES}

    def executables(self):
        return dict(self.paths)


def completed(checker):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        result = checker.snapshot()
        if result["runtime"]["checked"]:
            return result
        time.sleep(.01)
    pytest.fail("Diagnostic fixture did not finish")


def fixture_ready(name, path, log_dir, timeout):
    return dict(status="ready", reason="ok", exit=0, help_available=True)


def test_missing_tools_never_start_processes_or_report_ready():
    settings = FixtureSettings()
    settings.paths = {}
    checker = NativeReadiness(settings, probe=lambda *args: pytest.fail("Missing tools must not run"))
    try:
        result = checker.snapshot()
        assert result["ready"] is False
        assert result["runtime"]["checked"] is True and result["runtime"]["checking"] is False
        assert all(not value for value in result["tools"].values())
        assert all(row["status"] == "missing" for row in result["runtime"]["tools"].values())
        assert checker.thread is None
    finally:
        checker.close()


def test_found_but_blocked_binary_is_not_ready_and_diagnostic_is_sanitized():
    def blocked(name, *args):
        return dict(status="error", reason="startup_failed", exit=0xC0E90002,
                    output="private-output-token", detail="private-path-token")
    checker = NativeReadiness(FixtureSettings(), probe=blocked)
    try:
        result = completed(checker)
        assert all(result["tools"].values()) and result["ready"] is False
        assert result["runtime"]["tools"]["DensifyPointCloud"]["hex_exit"] == "0xc0e90002"
        serialized = json.dumps(result)
        assert "private-output-token" not in serialized and "private-path-token" not in serialized
        assert "fixture-colmap" not in serialized
    finally:
        checker.close()


def test_parallel_requests_share_one_nonblocking_diagnostic_batch():
    release = threading.Event()
    calls = []
    def pending(name, *args):
        calls.append(name)
        assert release.wait(5)
        return fixture_ready(name, *args)
    checker = NativeReadiness(FixtureSettings(), probe=pending)
    try:
        with ThreadPoolExecutor(max_workers=10) as executor:
            requests = [executor.submit(checker.snapshot) for _ in range(10)]
            results = [future.result(timeout=2) for future in requests]
        assert all(result["ready"] is False and result["runtime"]["checking"] for result in results)
        release.set()
        assert completed(checker)["ready"] is True
        assert sorted(calls) == sorted(TOOL_NAMES)
    finally:
        release.set()
        checker.close()


def test_expired_success_is_unchecked_until_the_new_failed_probe_finishes():
    now = [0.0]
    generation = [True]
    release = threading.Event()
    def probe(name, *args):
        if generation[0]:
            return fixture_ready(name, *args)
        assert release.wait(5)
        return dict(status="error", reason="timeout")
    checker = NativeReadiness(FixtureSettings(), probe=probe, clock=lambda: now[0], ttl=10)
    try:
        assert completed(checker)["ready"] is True
        generation[0] = False
        now[0] = 11
        result = checker.snapshot()
        assert result["ready"] is False and result["runtime"]["checking"] is True
        assert result["runtime"]["checked_at"] is None
        release.set()
        result = completed(checker)
        assert result["ready"] is False
        assert all(row["reason"] == "timeout" for row in result["runtime"]["tools"].values())
    finally:
        release.set()
        checker.close()


def test_changed_tool_paths_cannot_reuse_cached_success():
    settings = FixtureSettings()
    def probe(name, path, *args):
        return fixture_ready(name, path, *args) if path.startswith("fixture-") else dict(status="error", reason="launch_failed")
    checker = NativeReadiness(settings, probe=probe)
    try:
        assert completed(checker)["ready"] is True
        settings.paths["TextureMesh"] = "changed-binary"
        assert checker.snapshot()["ready"] is False
        assert completed(checker)["runtime"]["tools"]["TextureMesh"]["status"] == "error"
    finally:
        checker.close()


def test_inflight_old_paths_cannot_publish_ready_for_new_paths():
    settings = FixtureSettings()
    release = threading.Event()
    def probe(name, path, *args):
        if path.startswith("fixture-"):
            assert release.wait(5)
            return fixture_ready(name, path, *args)
        return dict(status="error", reason="launch_failed")
    checker = NativeReadiness(settings, probe=probe)
    try:
        assert checker.snapshot()["runtime"]["checking"] is True
        settings.paths = {name: "changed-" + name for name in TOOL_NAMES}
        assert checker.snapshot()["ready"] is False
        release.set()
        result = completed(checker)
        assert result["ready"] is False
        assert all(row["status"] == "error" for row in result["runtime"]["tools"].values())
    finally:
        release.set()
        checker.close()


def test_closed_checker_cannot_start_or_approve_more_work():
    checker = NativeReadiness(FixtureSettings(), probe=fixture_ready)
    assert completed(checker)["ready"] is True
    checker.close()
    result = checker.snapshot()
    assert result["ready"] is False and result["runtime"]["checked"] is False
    assert result["runtime"]["checking"] is False


@pytest.mark.parametrize("kwargs", [{"timeout": 0}, {"timeout": 121}, {"ttl": 0}, {"ttl": float("nan")}])
def test_invalid_diagnostic_limits_are_rejected(kwargs):
    with pytest.raises(ValueError):
        NativeReadiness(FixtureSettings(), **kwargs)


def test_real_python_help_does_not_impersonate_colmap(tmp_path):
    # Real process deliberately points at the wrong tool, not a fake native success.
    result = probe_tool("colmap", sys.executable, tmp_path, 5)
    assert result["exit"] == 0
    assert result["status"] == "error" and result["reason"] == "invalid_help"


def test_native_exit_125_is_not_assumed_to_be_docker(monkeypatch, tmp_path):
    monkeypatch.setattr(native_runtime.subprocess, "run", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args[0], 125, b"", b"fixture native failure"))
    result = probe_tool("colmap", "fixture-colmap", tmp_path, 5)
    assert result["status"] == "error" and result["reason"] == "startup_failed"


def test_openmvs_log_file_help_is_recognized_but_nonhelp_exit_one_is_not(monkeypatch, tmp_path):
    def logged_help(command, **kwargs):
        (kwargs["cwd"] / "fixture.log").write_text("OpenMVS 2.4\nAvailable options: fixture", encoding="utf-8")
        return subprocess.CompletedProcess(command, 1, b"", b"")
    monkeypatch.setattr(native_runtime.subprocess, "run", logged_help)
    assert probe_tool("TextureMesh", "fixture-mvs", tmp_path / "help", 5)["status"] == "ready"
    monkeypatch.setattr(native_runtime.subprocess, "run", lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 1, b"loader failed", b""))
    result = probe_tool("TextureMesh", "fixture-mvs", tmp_path / "failed", 5)
    assert result["status"] == "error" and result["help_available"] is False


def test_api_distinguishes_checking_and_failure_without_jobs_or_leaking_details(tmp_path):
    token = "diagnostic-test-only-token"
    settings = Settings(data_dir=tmp_path, token=token)
    settings.executables = FixtureSettings().executables
    app = create_app(settings, start_worker=False)
    release = threading.Event()
    def blocked(name, *args):
        assert release.wait(5)
        return dict(status="error", reason="startup_failed", exit=0xC0E90002, output=token, detail=str(tmp_path))
    app.state.native.probe = blocked
    try:
        with TestClient(app, headers={"Authorization": "Bearer " + token}) as client:
            initial = client.get("/capabilities").json()
            assert initial["ready"] is False and initial["runtime"]["checking"] is True
            assert client.get("/health").json()["status"] == "ok"
            release.set()
            completed(app.state.native)
            result = client.get("/capabilities")
            assert result.status_code == 200 and result.json()["ready"] is False
            assert all(result.json()["tools"].values())
            assert result.json()["runtime"]["tools"]["TextureMesh"]["hex_exit"] == "0xc0e90002"
            assert token not in result.text and str(tmp_path) not in result.text
            assert client.get("/jobs").json() == []
            assert not list(tmp_path.glob("incoming-*"))
    finally:
        release.set()
        app.state.native.close()


def pipeline_fixture(tmp_path, probe):
    settings = Settings(data_dir=tmp_path)
    settings.executables = FixtureSettings().executables
    store = JobStore(tmp_path)
    job = store.create({"name": "preflight fixture"}, ["0000.mp4"])
    uploads = tmp_path / job["id"] / "uploads"
    uploads.mkdir(parents=True)
    (uploads / "0000.mp4").write_bytes(b"preserved test input")
    checker = NativeReadiness(settings, probe=probe)
    return Pipeline(store, settings, native=checker), job, checker


def test_worker_failure_before_image_preparation_preserves_input_and_old_attempt(tmp_path):
    def blocked(name, *args):
        return dict(status="error", reason="startup_failed", exit=0xC0E90002)
    pipeline, job, checker = pipeline_fixture(tmp_path, blocked)
    root = tmp_path / job["id"]
    old = root / "attempt-old"
    old.mkdir()
    (old / "pipeline.log").write_text("old attempt evidence", encoding="utf-8")
    try:
        pipeline.process(job)
        failed = pipeline.store.get(job["id"])
        assert failed["status"] == "failed" and failed["stage"] == "checking_tools"
        assert failed["progress"] == 0 and "0xc0e90002" in failed["error"]
        assert list(root.glob("attempt-*")) == [old]
        assert (old / "pipeline.log").read_text() == "old attempt evidence"
        assert (root / "uploads" / "0000.mp4").read_bytes() == b"preserved test input"
        assert not (root / "model.glb").exists()
        retry = pipeline.store.update(job["id"], status="queued")
        pipeline.process(retry)
        assert pipeline.store.get(job["id"])["stage"] == "checking_tools"
        assert list(root.glob("attempt-*")) == [old]
    finally:
        checker.close()


def test_calibration_preflight_only_requires_blender_not_openmvs(tmp_path):
    def blender_only(name, *args):
        return fixture_ready(name, *args) if name == "blender" else dict(status="error", reason="startup_failed")
    pipeline, job, checker = pipeline_fixture(tmp_path, blender_only)
    pipeline.store.update(job["id"], status="calibrating")
    try:
        pipeline.preflight(job["id"], calibration=True)
        assert pipeline.store.get(job["id"])["status"] == "calibrating"
        with pytest.raises(RuntimeError, match="Native tools failed startup"):
            pipeline.preflight(job["id"], calibration=False)
    finally:
        checker.close()


def test_cancellation_during_native_preflight_does_not_create_an_attempt(tmp_path):
    release, started = threading.Event(), threading.Event()
    def pending(name, *args):
        started.set()
        assert release.wait(5)
        return fixture_ready(name, *args)
    pipeline, job, checker = pipeline_fixture(tmp_path, pending)
    thread = threading.Thread(target=pipeline.process, args=(job,))
    try:
        thread.start()
        assert started.wait(2)
        pipeline.store.update(job["id"], status="cancelled", stage="cancelled")
        thread.join(timeout=2)
        assert not thread.is_alive()
        assert pipeline.store.get(job["id"])["status"] == "cancelled"
        assert not list((tmp_path / job["id"]).glob("attempt-*"))
    finally:
        release.set()
        thread.join(timeout=5)
        checker.close()
