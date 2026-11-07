"""Resource/exit regressions; mocked Docker is not real reconstruction proof."""
import subprocess
import sys

import pytest

from spacefit.config import Settings
from spacefit.docker_runtime import DockerConfig, OpenMvsDocker
from spacefit.pipeline import Pipeline
from spacefit.store import JobStore


def fixture(tmp_path):
    config = DockerConfig(binary="fixture-docker", image="sha256:" + "a" * 64)
    settings = Settings(data_dir=tmp_path, openmvs_runtime="docker", docker=config)
    settings.executables = lambda: {"RefineMesh": config.binary}
    store = JobStore(tmp_path)
    job = store.create({"name": "memory contract fixture"}, [], status="processing")
    work = tmp_path / job["id"] / "attempt-fixture"
    work.mkdir(parents=True)
    return Pipeline(store, settings), job["id"], work


def test_refine_reduces_parallel_scratch_without_changing_resolution(tmp_path, monkeypatch):
    pipeline, job_id, work = fixture(tmp_path)
    calls = []
    outputs = ["scene_dense_mesh.mvs", "scene_dense_mesh_refine.mvs", "scene_dense_mesh_refine_texture.obj"]
    def run(*args):
        calls.append(args)
        (work / outputs[len(calls) - 1]).write_bytes(b"explicit mocked output")
    monkeypatch.setattr(pipeline, "run_mvs", run)
    pipeline.reconstruct_mesh(job_id, work)
    args = calls[1][4]
    assert args[args.index("--max-threads") + 1] == "1"
    assert args[args.index("--resolution-level") + 1] == "0"
    assert args[args.index("--reduce-memory") + 1] == "1"
    assert "--decimate" not in args and "--max-views" not in args
    assert [call[3] for call in calls] == ["ReconstructMesh", "RefineMesh", "TextureMesh"]


def test_refine_docker_cpu_and_openmp_match_single_native_thread(tmp_path, monkeypatch):
    pipeline, job_id, work = fixture(tmp_path)
    calls = []
    monkeypatch.setattr(pipeline, "run", lambda *args: calls.append(args))
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 0, b"", b""))
    pipeline.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene.mvs", "--max-threads", "1"], work)
    argv = calls[0][3]
    assert argv[argv.index("--cpus") + 1] == "1"
    assert "OMP_NUM_THREADS=1" in argv
    assert argv[argv.index("--memory") + 1] == "4096m"
    assert pipeline.settings.docker.threads == 4  # Other stages unchanged.


@pytest.mark.parametrize("flag,value", [("--reduce-memory", "1"), ("--resolution-level", "0")])
def test_refine_resource_flags_are_scoped_and_validated(tmp_path, flag, value):
    pipeline, _, work = fixture(tmp_path)
    adapter = OpenMvsDocker(pipeline.settings.docker)
    prepared = adapter.prepare("RefineMesh", ["scene.mvs", flag, value], work)
    assert prepared.argv[-2:] == [flag, value]
    for invalid in ("-1", "5", "true", "1;sh", "", 1):
        with pytest.raises(ValueError):
            adapter.prepare("RefineMesh", ["scene.mvs", flag, invalid], work)
    with pytest.raises(ValueError):
        adapter.prepare("TextureMesh", ["scene.mvs", flag, value], work)
    with pytest.raises(ValueError):
        adapter.prepare("RefineMesh", ["scene.mvs", flag, value, flag, value], work)


def test_real_exit_preserves_machine_readable_code_without_claiming_oom(tmp_path):
    pipeline, job_id, work = fixture(tmp_path)
    with pytest.raises(RuntimeError) as caught:
        pipeline.run(job_id, "refine", 80, [sys.executable, "-c", "raise SystemExit(137)"], work)
    assert getattr(caught.value, "exit_code", None) == 137
    assert "out of memory" not in str(caught.value).lower()
    assert (work / "pipeline.log").is_file()


@pytest.mark.parametrize("event,code,prefix", [
    (b"oom\n", 137, "NATIVE_OUT_OF_MEMORY:"),
    (b"", 137, "NATIVE_KILLED:"),
    (b"private-test-token", 137, "NATIVE_KILLED:"),
    (b"oom\n", 1, "refine failed (exit 1)"),
])
def test_docker_classifies_only_confirmed_oom_and_cleans_owned_attempt(tmp_path, monkeypatch, event, code, prefix):
    from spacefit.pipeline import NativeCommandError
    pipeline, job_id, work = fixture(tmp_path)
    sentinel = work / "original-fixture.txt"
    sentinel.write_text("preserved", encoding="utf-8")
    commands = []
    def run(*args):
        raise NativeCommandError("refine", code)
    def docker(argv, **kwargs):
        commands.append(argv)
        output = b"2030-01-02T03:04:05.123456789Z\n" if "info" in argv else event if "events" in argv else b""
        return subprocess.CompletedProcess(argv, 0, output, b"")
    monkeypatch.setattr(pipeline, "run", run)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker)
    with pytest.raises(RuntimeError) as caught:
        pipeline.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene.mvs"], work)
    assert str(caught.value).startswith(prefix)
    assert "private-test-token" not in str(caught.value)
    assert commands[-1][-4:-1] == ["container", "rm", "--force"]
    if code == 137:
        query = commands[1]
        assert "event=oom" in query and "--since" in query and "--until" in query
        assert "container=" + commands[-1][-1] in query
        assert query[query.index("--until") + 1] == "2030-01-02T03:04:05.123456789Z"
        assert query[query.index("--since") + 1] == "0"
    else:
        assert len(commands) == 1
    assert sentinel.read_text() == "preserved" and not (work / "model.glb").exists()


def test_oom_probe_failure_keeps_original_failure_and_still_cleans(tmp_path, monkeypatch):
    from spacefit.pipeline import NativeCommandError
    pipeline, job_id, work = fixture(tmp_path)
    calls = []
    def run(*args):
        raise NativeCommandError("refine", 137)
    def docker(argv, **kwargs):
        calls.append(argv)
        if "events" in argv:
            raise subprocess.TimeoutExpired(argv, 5, output=b"private fixture")
        return subprocess.CompletedProcess(argv, 0, b"2030-01-02T03:04:05Z" if "info" in argv else b"", b"")
    monkeypatch.setattr(pipeline, "run", run)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker)
    with pytest.raises(RuntimeError, match="^NATIVE_KILLED:") as caught:
        pipeline.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene.mvs"], work)
    assert "private" not in str(caught.value) and len(calls) == 3


@pytest.mark.parametrize("kind", ["cancel", "retry"])
def test_state_change_during_oom_diagnosis_stops_old_attempt(tmp_path, monkeypatch, kind):
    from spacefit.pipeline import NativeCommandError, Cancelled
    pipeline, job_id, work = fixture(tmp_path)
    calls = []
    def run(*args):
        raise NativeCommandError("refine", 137)
    def docker(argv, **kwargs):
        calls.append(argv)
        if "events" in argv:
            pipeline.store.update(job_id, status="cancelled" if kind == "cancel" else "queued")
        return subprocess.CompletedProcess(argv, 0, b"2030-01-02T03:04:05Z" if "info" in argv else b"oom\n", b"")
    monkeypatch.setattr(pipeline, "run", run)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker)
    with pytest.raises(Cancelled):
        pipeline.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene.mvs"], work)
    assert len(calls) == 3


def test_diagnostic_refuses_foreign_container_and_nonzero_reply(tmp_path, monkeypatch):
    pipeline, _, work = fixture(tmp_path)
    prepared = OpenMvsDocker(pipeline.settings.docker).prepare("RefineMesh", ["-h"], work)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 1, b"oom\n", b"private fixture"))
    assert prepared.observed_oom() is False
    prepared.container_name = "foreign-container"
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *a, **k: pytest.fail("No foreign query"))
    assert prepared.observed_oom() is False


def test_worker_persists_memory_failure_without_success_fallback_or_input_loss(tmp_path, monkeypatch):
    from spacefit.pipeline import NativeCommandError
    pipeline, job_id, work = fixture(tmp_path)
    job = pipeline.store.update(job_id, status="calibration_queued")
    pipeline.settings.executables = lambda: {"blender": "fixture-blender", "RefineMesh": "fixture-docker"}
    marker = work / "input-fixture.txt"
    marker.write_text("unchanged", encoding="utf-8")
    monkeypatch.setattr(pipeline, "preflight", lambda *a: None)
    def run(job_id, stage, progress, *args):
        pipeline.store.update(job_id, stage=stage, progress=progress)
        raise NativeCommandError(stage, 137)
    monkeypatch.setattr(pipeline, "run", run)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 0, b"2030-01-02T03:04:05Z" if "info" in a[0] else b"oom\n", b""))
    # Explicit orchestration fixture: route export to a failing Docker command,
    # no successful Blender or photogrammetry is simulated.
    monkeypatch.setattr(pipeline, "export", lambda *a, **k:
                        pipeline.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene.mvs"], work))
    pipeline.process(job)
    saved = pipeline.store.get(job_id)
    assert saved["status"] == "failed" and saved["progress"] == 80
    assert saved["error"].startswith("NATIVE_OUT_OF_MEMORY:")
    assert saved["result"] is None and marker.read_text() == "unchanged"
    assert not (work.parent / "preview.glb").exists()


@pytest.mark.parametrize("reply", [b"", b"private fixture", b"2030-01-02T03:04:05", b"2030-01-02T03:04:05Z\nprivate"])
def test_invalid_daemon_clock_is_unknown_and_never_starts_events_stream(tmp_path, monkeypatch, reply):
    pipeline, _, work = fixture(tmp_path)
    prepared = OpenMvsDocker(pipeline.settings.docker).prepare("RefineMesh", ["-h"], work)
    calls = []
    def run(argv, **kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, reply, b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", run)
    assert prepared.observed_oom() is False
    assert len(calls) == 1 and "info" in calls[0]
