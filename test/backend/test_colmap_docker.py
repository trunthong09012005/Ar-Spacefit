"""CPU provider/pipeline contracts. Mocked replies are NOT native evidence."""
from dataclasses import replace
import json
import struct
import subprocess

import pytest

from spacefit.config import Settings
from spacefit.docker_runtime import ColmapDocker, DockerConfig
from spacefit.native_runtime import probe_configured_tool
from spacefit.pipeline import Pipeline, Cancelled
from spacefit.store import JobStore


def config():
    return DockerConfig(binary="fixture-docker", image="sha256:" + "a" * 64)


def commands():
    return [
        ["feature_extractor", "--database_path", "database.db", "--image_path", "images", "--ImageReader.camera_model", "SIMPLE_RADIAL", "--SiftExtraction.use_gpu", "0", "--SiftExtraction.num_threads", "4"],
        ["exhaustive_matcher", "--database_path", "database.db", "--SiftMatching.use_gpu", "0", "--SiftMatching.num_threads", "4"],
        ["mapper", "--database_path", "database.db", "--image_path", "images", "--output_path", "sparse", "--Mapper.num_threads", "4"],
        ["image_undistorter", "--image_path", "images", "--input_path", "sparse/0", "--output_path", "dense", "--output_type", "COLMAP", "--max_image_size", "2000"],
    ]


@pytest.mark.parametrize("args", commands())
def test_four_commands_translate_only_paths_and_enforce_isolated_cpu_argv(tmp_path, args):
    prepared = ColmapDocker(config()).prepare("colmap", args, tmp_path)
    argv = prepared.argv
    assert argv[-len(args)-1] == "colmap"
    assert argv[argv.index("--mount")+1] == f"type=bind,source={tmp_path.resolve()},target=/workspace"
    assert argv[argv.index("--network")+1] == "none"
    assert argv[argv.index("--memory")+1] == "4096m"
    assert argv[argv.index("--cpus")+1] == "4"
    assert "--read-only" in argv and "--privileged" not in argv and "--gpus" not in argv
    assert argv[argv.index("--ulimit")+1] == "core=0"
    assert "ar-spacefit.native=colmap" in argv
    assert prepared.container_name.startswith("spacefit-colmap-")
    assert config().image in argv
    for i, value in enumerate(args):
        if value.endswith("_path"):
            assert argv[argv.index(value)+1] == "/workspace/" + args[i+1]


@pytest.mark.parametrize("args", [["-h"], *[[c, "-h"] for c in ColmapDocker.commands]])
def test_supported_help_is_fixed_argv(tmp_path, args):
    assert ColmapDocker(config()).prepare("colmap", args, tmp_path).argv[-len(args):] == args


@pytest.mark.parametrize("bad", ["../other/database.db", "/etc/passwd", "bad\npath", "bad\x00path"])
def test_path_escape_or_control_characters_are_rejected(tmp_path, bad):
    args = commands()[0]
    args[2] = bad
    with pytest.raises(ValueError):
        ColmapDocker(config()).prepare("colmap", args, tmp_path)


@pytest.mark.parametrize("args", [[], ["gui"], ["shell", "-h"], ["feature_extractor", "--project_path", "secret"],
    commands()[0] + ["--database_path", "other.db"], commands()[0][:-2],
    ["exhaustive_matcher", "--database_path", "db", "--SiftMatching.use_gpu", "1", "--SiftMatching.num_threads", "4"],
    ["exhaustive_matcher", "--database_path", "db", "--SiftMatching.use_gpu", "0", "--SiftMatching.num_threads", "64"],
    commands()[3][:-1] + ["-1"], commands()[3][:-3] + ["PLY", "--max_image_size", "2000"]])
def test_unsupported_and_resource_escaping_arguments_are_rejected(tmp_path, args):
    with pytest.raises(ValueError):
        ColmapDocker(config()).prepare("colmap", args, tmp_path)


def test_settings_fail_closed_and_do_not_reuse_windows_colmap(monkeypatch, tmp_path):
    cfg = config()
    with pytest.raises(ValueError, match="CPU-only"):
        Settings(data_dir=tmp_path, colmap_runtime="docker", colmap_docker=cfg, use_gpu=True)
    with pytest.raises(ValueError, match="COLMAP_RUNTIME"):
        Settings(data_dir=tmp_path, colmap_runtime="shell")
    monkeypatch.setenv("SPACEFIT_COLMAP_DOCKER_CONFIG", str(tmp_path / "missing.json"))
    with pytest.raises(FileNotFoundError):
        Settings(data_dir=tmp_path, colmap_runtime="docker")
    settings = Settings(data_dir=tmp_path, colmap_runtime="docker", colmap_docker=cfg)
    monkeypatch.setattr("spacefit.config.shutil.which", lambda path: "resolved-" + path)
    assert settings.executables()["colmap"] == "resolved-fixture-docker"
    original = settings.native_identity()
    for field, value in [("image", "sha256:"+"b"*64), ("context", "other"), ("threads", 2), ("memory_mb", 2048)]:
        settings.colmap_docker = replace(cfg, **{field:value})
        assert settings.native_identity() != original
    settings.colmap_docker = cfg
    settings.data_dir = tmp_path / "other-drive-scope"
    assert settings.native_identity() != original


def test_settings_load_explicit_pinned_colmap_config_without_reading_windows_path(monkeypatch, tmp_path):
    path = tmp_path / "colmap-config-fixture.json"
    path.write_text(json.dumps(dict(docker_bin="fixture-docker", image=config().image, threads=2, memory_mb=2048)), encoding="utf-8-sig")
    monkeypatch.setenv("SPACEFIT_COLMAP_DOCKER_CONFIG", str(path))
    settings = Settings(data_dir=tmp_path, colmap_runtime="docker")
    assert settings.colmap_docker == replace(config(), threads=2, memory_mb=2048)
    assert settings.docker is None and settings.openmvs_runtime == "native"


def test_readiness_executes_container_and_never_native_dll(monkeypatch, tmp_path):
    calls = []
    def reply(argv, **kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, b"COLMAP 3.9.1\nUsage:\n", b"")
    monkeypatch.setattr(subprocess, "run", reply)
    settings = Settings(data_dir=tmp_path, colmap_runtime="docker", colmap_docker=config())
    result = probe_configured_tool(settings, "colmap", "resolved-docker", tmp_path / "diagnostic", 10)
    assert result["status"] == "ready"
    assert calls[0][0] == "resolved-docker" and calls[0][-2:] == ["colmap", "-h"]
    assert calls[-1][3:5] == ["container", "rm"]
    assert calls[-1][-1] == calls[0][calls[0].index("--name")+1]


def pipeline_fixture(tmp_path):
    settings = Settings(data_dir=tmp_path, colmap_runtime="docker", colmap_docker=config())
    settings.executables = lambda: {"colmap":"fixture-docker", "blender":"fixture-blender"}
    store = JobStore(tmp_path)
    job = store.create({"name":"Explicit contract fixture"}, [], status="processing")
    work = tmp_path / job["id"] / "attempt-fixture"
    work.mkdir(parents=True)
    return Pipeline(store, settings), job, work


@pytest.mark.parametrize("failure", [None, Cancelled(), RuntimeError("stage fixture failure")])
def test_pipeline_always_cleans_only_its_container(monkeypatch, tmp_path, failure):
    pipeline, job, work = pipeline_fixture(tmp_path)
    runs, cleanups = [], []
    def run(*args):
        runs.append(args)
        if failure:
            raise failure
    monkeypatch.setattr(pipeline, "run", run)
    monkeypatch.setattr(subprocess, "run", lambda argv, **kw: (cleanups.append(argv) or subprocess.CompletedProcess(argv, 0, b"", b"")))
    if failure:
        with pytest.raises(type(failure)):
            pipeline.run_colmap(job["id"], "features", 12, commands()[0], work)
    else:
        pipeline.run_colmap(job["id"], "features", 12, commands()[0], work)
    command = runs[0][3]
    assert cleanups[0][-1] == command[command.index("--name")+1]
    assert command[command.index("--mount")+1] == f"type=bind,source={work.resolve()},target=/workspace"
    with pytest.raises(ValueError, match="scoped"):
        pipeline.run_colmap(job["id"], "features", 12, commands()[0], tmp_path)


def test_entire_pipeline_routes_four_colmap_stages_through_cpu_provider(monkeypatch, tmp_path):
    pipeline, job, _ = pipeline_fixture(tmp_path)
    pipeline.store.update(job["id"], status="queued")
    monkeypatch.setattr(pipeline, "preflight", lambda *a: None)
    monkeypatch.setattr("spacefit.pipeline.prepare_images", lambda *a: {"accepted_images":10})
    seen = []
    def fake_run(job_id, stage, progress, argv, work):
        assert argv[0] == "fixture-docker"
        assert "colmap" in argv
        seen.append(argv[argv.index("colmap")+1])
        if stage == "camera_poses":
            target = work / "sparse/0"
            target.mkdir()
            (target / "images.bin").write_bytes(struct.pack("<Q", 10))
    monkeypatch.setattr(pipeline, "run", fake_run)
    def fake_mvs(job_id, stage, progress, tool, args, work):
        # COLMAP-routing component test only: model the required file handoff
        # explicitly; real OpenMVS output is exercised in its opt-in suite.
        outputs = {"mvs_import": "scene.mvs", "dense_cloud": "scene_dense.mvs",
                   "mesh": "scene_dense_mesh.mvs", "refine": "scene_dense_mesh_refine.mvs",
                   "texture": "scene_dense_mesh_refine_texture.obj"}
        if stage != "mvs_import":
            assert args[args.index("--archive-type") + 1] == "1"
            assert (work / args[0]).is_file()
        (work / outputs[stage]).write_bytes(b"explicit mocked MVS output for COLMAP routing")
    monkeypatch.setattr(pipeline, "run_mvs", fake_mvs)
    monkeypatch.setattr(pipeline, "export", lambda *a, **kw: None)
    # Only cleanup may invoke subprocess.run; any direct Windows feature help is a bug.
    def cleanup(argv, **kw):
        assert argv[3:5] == ["container", "rm"]
        return subprocess.CompletedProcess(argv, 0, b"", b"")
    monkeypatch.setattr(subprocess, "run", cleanup)
    pipeline.process(pipeline.store.get(job["id"]))
    assert seen == ["feature_extractor", "exhaustive_matcher", "mapper", "image_undistorter"]
    assert pipeline.store.get(job["id"])["error"] is None
