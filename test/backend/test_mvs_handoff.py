"""Mocked native file contracts + real isolated JobStore; not reconstruction proof."""
import struct

import pytest

from spacefit.config import Settings
from spacefit.native_runtime import TOOL_NAMES
from spacefit.pipeline import Pipeline, Cancelled
from spacefit.store import JobStore


@pytest.fixture
def surface(tmp_path):
    store = JobStore(tmp_path)
    job = store.create({"name": "handoff contract fixture"}, [], status="processing")
    work = tmp_path / job["id"] / "attempt-fixture"
    work.mkdir(parents=True)
    (work / "scene_dense.mvs").write_bytes(b"explicit mocked dense scene")
    return Pipeline(store, Settings(data_dir=tmp_path)), job, work


OUTPUTS = {"mesh": "scene_dense_mesh.mvs", "refine": "scene_dense_mesh_refine.mvs",
           "texture": "scene_dense_mesh_refine_texture.obj"}


def native_contract(pipeline, work, calls, bad_stage=None, kind="missing"):
    def run(job_id, stage, progress, tool, args, cwd):
        calls.append((stage, tool, args))
        assert cwd == work
        pipeline.store.update(job_id, stage=stage, progress=progress)
        assert (work / args[0]).is_file(), "Next stage must consume an existing scene"
        assert args[args.index("--archive-type") + 1] == "1"
        assert args[args.index("--max-threads") + 1] == ("1" if stage == "refine" else str(pipeline.settings.threads))
        target = work / OUTPUTS[stage]
        if stage == bad_stage:
            if kind == "empty":
                target.touch()
            elif kind == "directory":
                target.mkdir()
        else:
            target.write_bytes(b"explicit mocked native output")
    return run


def test_binary_scene_contract_is_explicit_at_every_surface_stage(surface, monkeypatch):
    pipeline, job, work = surface
    calls = []
    monkeypatch.setattr(pipeline, "run_mvs", native_contract(pipeline, work, calls))
    pipeline.reconstruct_mesh(job["id"], work)
    assert [(stage, tool) for stage, tool, _ in calls] == [
        ("mesh", "ReconstructMesh"), ("refine", "RefineMesh"), ("texture", "TextureMesh")]
    assert calls[-1][2][1:3] == ["--export-type", "obj"]


@pytest.mark.parametrize("stage", OUTPUTS)
@pytest.mark.parametrize("kind", ["missing", "empty", "directory"])
def test_missing_or_invalid_output_stops_before_next_stage(surface, monkeypatch, stage, kind):
    pipeline, job, work = surface
    calls = []
    monkeypatch.setattr(pipeline, "run_mvs", native_contract(pipeline, work, calls, stage, kind))
    with pytest.raises(RuntimeError, match=f"{stage} did not produce {OUTPUTS[stage]}"):
        pipeline.reconstruct_mesh(job["id"], work)
    assert calls[-1][0] == stage
    assert pipeline.store.get(job["id"])["stage"] == stage
    assert (work / "scene_dense.mvs").read_bytes() == b"explicit mocked dense scene"


def test_cancel_between_mesh_and_refine_cannot_continue(surface, monkeypatch):
    pipeline, job, work = surface
    calls = []
    generate = native_contract(pipeline, work, calls)
    def cancel_after_mesh(*args):
        generate(*args)
        pipeline.store.update(job["id"], status="cancelled")
    monkeypatch.setattr(pipeline, "run_mvs", cancel_after_mesh)
    with pytest.raises(Cancelled):
        pipeline.reconstruct_mesh(job["id"], work)
    assert len(calls) == 1


@pytest.mark.parametrize("missing_dense", [False, True])
def test_process_requests_dense_binary_archive_and_checks_it_before_surface(tmp_path, monkeypatch, missing_dense):
    """Component orchestration only: all native/image operations are explicit mocks."""
    settings = Settings(data_dir=tmp_path)
    settings.executables = lambda: {name: "fixture-tool" for name in TOOL_NAMES}
    store = JobStore(tmp_path)
    job = store.create({"name": "dense mocked fixture"}, [])
    pipeline = Pipeline(store, settings)
    monkeypatch.setattr(pipeline, "preflight", lambda *args: None)
    monkeypatch.setattr("spacefit.pipeline.prepare_images", lambda *args: {"accepted_images": 10})
    monkeypatch.setattr("spacefit.pipeline.subprocess.run", lambda *args, **kwargs:
                        type("FixtureHelp", (), {"stdout": "SiftExtraction.use_gpu", "stderr": ""})())
    def colmap(job_id, stage, progress, args, work):
        if stage == "camera_poses":
            model = work / "sparse" / "0"
            model.mkdir()
            (model / "images.bin").write_bytes(struct.pack("<Q", 10))
    monkeypatch.setattr(pipeline, "run_colmap", colmap)
    def mvs(job_id, stage, progress, tool, args, work):
        store.update(job_id, stage=stage)
        if stage == "dense_cloud":
            assert args[args.index("--archive-type") + 1] == "1"
            if not missing_dense:
                (work / "scene_dense.mvs").write_bytes(b"mocked dense binary")
    monkeypatch.setattr(pipeline, "run_mvs", mvs)
    surface_calls, exports = [], []
    monkeypatch.setattr(pipeline, "reconstruct_mesh", lambda *args: surface_calls.append(args))
    monkeypatch.setattr(pipeline, "export", lambda *args, **kwargs: exports.append(args))
    pipeline.process(job)
    result = store.get(job["id"])
    if missing_dense:
        assert result["status"] == "failed" and result["stage"] == "dense_cloud"
        assert "dense_cloud did not produce scene_dense.mvs" in result["error"]
        assert not surface_calls and not exports
    else:
        assert result.get("error") is None
        assert len(surface_calls) == len(exports) == 1
