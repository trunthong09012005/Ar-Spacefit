"""Docker command/security contracts; mocked Docker is NOT native success evidence.

Only new pytest-owned directories are mounted in these tests. No production job,
database, private image/video, or real container is accessed by this module.
"""
from dataclasses import replace
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time

import pytest

from spacefit.docker_runtime import DockerConfig, MVS_TOOLS, OpenMvsDocker
from spacefit.config import Settings
from spacefit.native_runtime import NativeReadiness, TOOL_NAMES, probe_configured_tool
from spacefit.pipeline import Cancelled, Pipeline
from spacefit.store import JobStore


IMAGE = "sha256:" + "a" * 64


def config(**kwargs):
    return DockerConfig(binary="fixture-docker.exe", image=IMAGE, **kwargs)


@pytest.mark.parametrize("image", [IMAGE, "registry.example/ar-spacefit/openmvs@sha256:" + "b" * 64,
                                   "registry.example:5000/ar-spacefit/openmvs@sha256:" + "b" * 64])
def test_configuration_requires_an_immutable_image_identity(image):
    value = DockerConfig(binary="fixture-docker.exe", image=image)
    assert value.image == image
    assert value.context == "desktop-linux" and value.threads == 4 and value.memory_mb == 4096


@pytest.mark.parametrize("image", [
    "", "openmvs", "openmvs:latest", "registry.example/openmvs:2.4", "sha256:" + "a" * 63,
    "sha256:" + "g" * 64, IMAGE + "\n", IMAGE + " --privileged", "repo:latest@" + IMAGE,
])
def test_mutable_malformed_or_option_injecting_images_are_rejected(image):
    with pytest.raises(ValueError):
        DockerConfig(binary="fixture-docker.exe", image=image)


@pytest.mark.parametrize("kwargs", [
    {"binary": ""}, {"binary": "docker\n--privileged"}, {"binary": "docker\x00exe"},
    {"context": ""}, {"context": "desktop-linux --host x"}, {"context": "x\n"},
    {"context": "x" * 65}, {"threads": 0}, {"threads": 65}, {"threads": True},
    {"threads": 1.5}, {"memory_mb": 255}, {"memory_mb": 65537}, {"memory_mb": True},
    {"memory_mb": 4096.5},
])
def test_invalid_configuration_cannot_construct_a_provider(kwargs):
    with pytest.raises(ValueError):
        DockerConfig(**{"binary": "fixture-docker.exe", "image": IMAGE, **kwargs})


def test_all_runtime_settings_participate_in_cache_identity():
    value = config()
    original = value.identity()
    variants = [replace(value, binary="other-docker.exe"), replace(value, image="sha256:" + "b" * 64),
                replace(value, context="other-context"), replace(value, threads=2),
                replace(value, memory_mb=2048)]
    assert all(variant.identity() != original for variant in variants)
    assert len({variant.identity() for variant in variants}) == len(variants)
    assert replace(value).identity() == original


@pytest.mark.parametrize("name", MVS_TOOLS)
def test_help_commands_have_only_a_confined_mount_and_hardened_flags(name, tmp_path):
    value = OpenMvsDocker(config()).prepare(name, ["-h"], tmp_path)
    argv = value.argv
    assert isinstance(argv, list) and all(isinstance(arg, str) for arg in argv)
    assert argv[:4] == ["fixture-docker.exe", "--context", "desktop-linux", "run"]
    assert argv[-3:] == [IMAGE, name, "-h"]
    assert argv[argv.index("--network") + 1] == "none"
    assert argv[argv.index("--cap-drop") + 1] == "ALL"
    assert argv[argv.index("--security-opt") + 1] == "no-new-privileges"
    assert "--read-only" in argv and "--rm" in argv
    assert argv[argv.index("--tmpfs") + 1] == "/tmp:rw,noexec,nosuid,size=256m"
    assert argv[argv.index("--memory") + 1] == "4096m"
    assert argv[argv.index("--cpus") + 1] == "4"
    assert argv[argv.index("--label") + 1] == "ar-spacefit.native=openmvs"
    assert argv[argv.index("--workdir") + 1] == "/workspace"
    assert argv.count("--mount") == 1
    assert argv[argv.index("--mount") + 1] == f"type=bind,source={tmp_path.resolve()},target=/workspace"
    assert argv[argv.index("--name") + 1] == value.container_name
    assert re.fullmatch(r"spacefit-mvs-[0-9a-f]{32}", value.container_name)
    assert value.cwd == tmp_path.resolve()
    assert not {"--privileged", "--gpus", "--volume", "-v", "--device", "-e"} & set(argv)
    assert argv.count("--env") == 1
    assert argv[argv.index("--env") + 1] == "OMP_NUM_THREADS=4"
    assert argv[argv.index("--pull") + 1] == "never"
    assert argv[argv.index("--pids-limit") + 1] == "256"
    assert "docker.sock" not in " ".join(argv)


def test_prepared_commands_do_not_share_container_names(tmp_path):
    provider = OpenMvsDocker(config())
    first = provider.prepare("TextureMesh", ["-h"], tmp_path)
    second = provider.prepare("TextureMesh", ["-h"], tmp_path)
    assert first.container_name != second.container_name


def test_interface_paths_translate_under_the_exact_attempt_directory(tmp_path):
    work = tmp_path / "owned attempt with spaces"
    work.mkdir()
    dense = work / "dense"
    (dense / "images").mkdir(parents=True)
    value = OpenMvsDocker(config()).prepare("InterfaceCOLMAP", [
        "-i", str(dense), "-o", "scene.mvs", "--image-folder", str(dense / "images")], work)
    assert value.argv[value.argv.index(IMAGE) + 1:] == [
        "InterfaceCOLMAP", "-i", "/workspace/dense", "-o", "/workspace/scene.mvs",
        "--image-folder", "/workspace/dense/images"]
    mount = value.argv[value.argv.index("--mount") + 1]
    assert str(work.resolve()) in mount and f"source={tmp_path.resolve()}," not in mount


@pytest.mark.parametrize("name,args,translated", [
    ("DensifyPointCloud", ["scene.mvs", "--resolution-level", "1", "--max-threads", "4"],
     ["/workspace/scene.mvs", "--resolution-level", "1", "--max-threads", "4"]),
    ("ReconstructMesh", ["scene_dense.mvs", "--max-threads", "1"],
     ["/workspace/scene_dense.mvs", "--max-threads", "1"]),
    ("RefineMesh", ["scene_dense_mesh.mvs", "--max-threads", "64"],
     ["/workspace/scene_dense_mesh.mvs", "--max-threads", "64"]),
    ("TextureMesh", ["scene_dense_mesh_refine.mvs", "--export-type", "obj", "--max-threads", "4"],
     ["/workspace/scene_dense_mesh_refine.mvs", "--export-type", "obj", "--max-threads", "4"]),
])
def test_production_pipeline_argument_shapes_are_supported(name, args, translated, tmp_path):
    value = OpenMvsDocker(config()).prepare(name, args, tmp_path)
    assert value.argv[value.argv.index(IMAGE) + 1:] == [name, *translated]


@pytest.mark.parametrize("name", ["sh", "bash", "python", "docker", "colmap", "blender", "TextureMesh;sh", "../TextureMesh"])
def test_only_the_five_declared_openmvs_programs_can_run(name, tmp_path):
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare(name, ["-h"], tmp_path)


@pytest.mark.parametrize("args", [
    ["--privileged"], ["--help", ";", "sh"], ["scene.mvs", "--config-file", "/etc/config"],
    ["scene.mvs", "--max-threads"], ["scene.mvs", "--max-threads", "0"],
    ["scene.mvs", "--max-threads", "65"], ["scene.mvs", "--max-threads", "1.2"],
    ["scene.mvs", "--max-threads", "4;sh"], ["scene.mvs", "--resolution-level", "-1"],
    ["scene.mvs", "--resolution-level", "5"], ["scene.mvs", "--export-type", "ply"],
    ["scene.mvs\x00"],
    ["scene.mvs", "--archive-type"],
    ["scene.mvs", "--archive-type", "1", "--archive-type", "1"],
])
def test_unknown_flags_missing_values_and_invalid_numeric_values_are_rejected(args, tmp_path):
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("TextureMesh", args, tmp_path)


@pytest.mark.parametrize("path", ["../foreign.mvs", "sub/../../foreign.mvs", "/etc/foreign.mvs", "/workspace/../../etc/foreign.mvs"])
def test_positional_input_paths_cannot_escape_the_mounted_attempt(path, tmp_path):
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("DensifyPointCloud", [path], tmp_path)


@pytest.mark.parametrize("flag", ["-i", "-o", "--image-folder"])
def test_named_input_and_output_paths_cannot_mount_or_access_foreign_data(flag, tmp_path):
    work = tmp_path / "attempt"
    work.mkdir()
    foreign = tmp_path / "private-foreign-path"
    args = ["-i", "dense", "-o", "scene.mvs", "--image-folder", "dense/images"]
    args[args.index(flag) + 1] = str(foreign)
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("InterfaceCOLMAP", args, work)


@pytest.mark.parametrize("resolution", ["0", "4"])
def test_density_resolution_limits_are_accepted(resolution, tmp_path):
    value = OpenMvsDocker(config()).prepare("DensifyPointCloud", ["scene.mvs", "--resolution-level", resolution], tmp_path)
    assert value.argv[-3:] == ["/workspace/scene.mvs", "--resolution-level", resolution]


@pytest.mark.parametrize("tool", MVS_TOOLS[1:])
def test_binary_archive_type_is_supported_for_pipeline_stages(tool, tmp_path):
    value = OpenMvsDocker(config()).prepare(tool, ["scene.mvs", "--archive-type", "1"], tmp_path)
    assert value.argv[-3:] == ["/workspace/scene.mvs", "--archive-type", "1"]


@pytest.mark.parametrize("value", ["-1", "0", "2", "NaN", "1;sh", "", "1.0"])
def test_only_the_explicit_binary_archive_contract_is_allowed(value, tmp_path):
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("ReconstructMesh", ["scene.mvs", "--archive-type", value], tmp_path)


@pytest.mark.parametrize("tool", ["colmap", "TextureMesh"])
def test_docker_exit_125_is_classified_without_leaking_daemon_output(tool, tmp_path, monkeypatch):
    settings = Settings(data_dir=tmp_path, openmvs_runtime="docker", docker=config(),
                        colmap_runtime="docker", colmap_docker=config())
    def reply(command, **kwargs):
        if "run" in command:
            return subprocess.CompletedProcess(command, 125, b"", b"private-daemon-path-token")
        return subprocess.CompletedProcess(command, 0, b"", b"")
    monkeypatch.setattr("spacefit.native_runtime.subprocess.run", reply)
    result = probe_configured_tool(settings, tool, "fixture-docker.exe", tmp_path / tool, 5)
    assert result["status"] == "error" and result["reason"] == "docker_start_failed"
    checker = NativeReadiness(settings, probe=lambda *args: result)
    settings.executables = lambda: {name: "fixture-tool" for name in TOOL_NAMES}
    try:
        state = completed(checker)
        assert state["ready"] is False
        assert state["runtime"]["tools"][tool]["reason"] == "docker_start_failed"
        assert state["runtime"]["tools"][tool]["hex_exit"] == "0x7d"
        assert "private-daemon-path-token" not in json.dumps(state)
    finally:
        checker.close()


@pytest.mark.parametrize("resolution", ["-1", "5", "NaN", "1.5"])
def test_density_resolution_outside_limits_or_not_an_integer_is_rejected(resolution, tmp_path):
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("DensifyPointCloud", ["scene.mvs", "--resolution-level", resolution], tmp_path)


@pytest.mark.parametrize("directory", ["comma,attempt", "newline\nattempt"])
def test_mount_parser_delimiters_are_rejected(directory, tmp_path):
    work = tmp_path / directory
    # Newline is itself an invalid Windows directory character. Comma is legal
    # to the filesystem, but a delimiter in Docker's --mount string parser.
    if "\n" not in directory:
        work.mkdir()
    with pytest.raises(ValueError):
        OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], work)


def test_nonexistent_or_regular_file_workdir_is_rejected(tmp_path):
    file = tmp_path / "not-a-directory"
    file.write_text("owned fixture", encoding="utf-8")
    for directory in (file, tmp_path / "missing-directory"):
        with pytest.raises(ValueError):
            OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], directory)


def test_resolved_directory_link_cannot_escape_mount_root(tmp_path):
    work, target = tmp_path / "attempt", tmp_path / "foreign"
    work.mkdir()
    target.mkdir()
    link = work / "linked"
    if os.name == "nt":
        # Junction fixture needs no developer-mode privilege. Delete only the
        # link in finally; target is another pytest-owned directory.
        result = subprocess.run(["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(target)],
                                capture_output=True, timeout=10)
        assert result.returncode == 0, "Could not construct the owned junction security fixture"
    else:
        link.symlink_to(target, target_is_directory=True)
    try:
        with pytest.raises(ValueError):
            OpenMvsDocker(config()).prepare("TextureMesh", [str(link / "foreign.mvs")], work)
    finally:
        os.rmdir(link) if os.name == "nt" else link.unlink()
    assert target.is_dir()


def test_container_cleanup_is_scoped_idempotent_and_never_uses_a_shell(monkeypatch, tmp_path):
    calls = []
    def docker_reply(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, b"", b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker_reply)
    value = OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], tmp_path)
    with value as entered:
        assert entered is value
    value.cleanup()
    assert len(calls) == 1
    command, kwargs = calls[0]
    assert command == ["fixture-docker.exe", "--context", "desktop-linux", "container", "rm", "--force", value.container_name]
    assert kwargs.get("shell", False) is False and kwargs["timeout"] == 10
    assert kwargs["capture_output"] is True
    assert not {"prune", "stop", "kill", "--all", "-a"} & set(command)


@pytest.mark.parametrize("failure", [RuntimeError("fixture stage failure"), subprocess.TimeoutExpired("fixture Docker", 1)])
def test_stage_failure_and_timeout_still_cleanup_only_the_owned_container(monkeypatch, tmp_path, failure):
    calls = []
    def docker_reply(command, **kwargs):
        calls.append(command)
        return subprocess.CompletedProcess(command, 0, b"", b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker_reply)
    value = OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], tmp_path)
    with pytest.raises(type(failure)) as seen:
        with value:
            raise failure
    assert seen.value is failure and len(calls) == 1
    assert calls[0][-1] == value.container_name


def test_cleanup_failure_cannot_mask_the_stage_error_or_leak_raw_output(monkeypatch, tmp_path):
    private = "private-test-token-do-not-return"
    def failed_cleanup(command, **kwargs):
        return subprocess.CompletedProcess(command, 1, b"", private.encode())
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", failed_cleanup)
    value = OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], tmp_path)
    original = RuntimeError("original stage error")
    with pytest.raises(RuntimeError) as seen:
        with value:
            raise original
    assert seen.value is original
    assert value.cleanup_error and private not in value.cleanup_error


def test_already_removed_container_is_not_reported_as_cleanup_failure(monkeypatch, tmp_path):
    value = OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], tmp_path)
    def missing_reply(command, **kwargs):
        return subprocess.CompletedProcess(command, 1, b"", ("Error: No such container: " + value.container_name).encode())
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", missing_reply)
    value.cleanup()
    assert value.cleanup_error is None


def test_cleanup_refuses_a_foreign_unowned_container_name(monkeypatch, tmp_path):
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *args, **kwargs: pytest.fail("Must not remove a foreign container"))
    value = OpenMvsDocker(config()).prepare("TextureMesh", ["-h"], tmp_path)
    value.container_name = "users-important-container"
    value.cleanup()
    assert value.cleanup_error


def test_settings_load_only_the_pinned_runtime_config_from_a_test_file(monkeypatch, tmp_path):
    path = tmp_path / "owned-native-config.json"
    path.write_text(json.dumps({"docker_bin": "fixture-docker.exe", "image": IMAGE,
                                "context": "fixture-context", "threads": 2, "memory_mb": 2048}), encoding="utf-8-sig")
    monkeypatch.setenv("SPACEFIT_OPENMVS_RUNTIME", "docker")
    monkeypatch.setenv("SPACEFIT_DOCKER_CONFIG", str(path))
    settings = Settings(data_dir=tmp_path)
    assert settings.openmvs_runtime == "docker"
    assert settings.docker == DockerConfig(binary="fixture-docker.exe", image=IMAGE,
                                           context="fixture-context", threads=2, memory_mb=2048)
    assert settings.native_identity() == ("docker", settings.docker.identity(), "native", None, str(tmp_path.resolve()))


@pytest.mark.parametrize("content", [
    "not-json", "{}", json.dumps({"docker_bin": "fixture-docker.exe", "image": "openmvs:latest"}),
    json.dumps({"docker_bin": "fixture-docker.exe", "image": IMAGE, "threads": 0}),
])
def test_docker_settings_do_not_fall_back_to_native_for_invalid_config(monkeypatch, tmp_path, content):
    path = tmp_path / "invalid-test-native-config.json"
    path.write_text(content, encoding="utf-8")
    monkeypatch.setenv("SPACEFIT_DOCKER_CONFIG", str(path))
    with pytest.raises((ValueError, KeyError)):
        Settings(data_dir=tmp_path, openmvs_runtime="docker")


def test_docker_settings_missing_config_file_is_an_error_not_native_fallback(monkeypatch, tmp_path):
    monkeypatch.setenv("SPACEFIT_DOCKER_CONFIG", str(tmp_path / "missing-test-config.json"))
    with pytest.raises(FileNotFoundError):
        Settings(data_dir=tmp_path, openmvs_runtime="docker")


@pytest.mark.parametrize("runtime", ["", "Docker", "remote", "docker --privileged"])
def test_unknown_runtime_env_values_are_rejected(runtime, monkeypatch, tmp_path):
    monkeypatch.setenv("SPACEFIT_OPENMVS_RUNTIME", runtime)
    with pytest.raises(ValueError):
        Settings(data_dir=tmp_path)


def test_docker_executable_resolution_replaces_only_the_five_openmvs_binaries(monkeypatch, tmp_path):
    monkeypatch.setenv("COLMAP_BIN", "fixture-colmap.exe")
    monkeypatch.setenv("BLENDER_BIN", "fixture-blender.exe")
    monkeypatch.setenv("OPENMVS_BIN_DIR", str(tmp_path / "ignored-native-mvs"))
    calls = []
    def which(path):
        calls.append(path)
        assert path in {"fixture-colmap.exe", "fixture-blender.exe", "fixture-docker.exe"}
        return "resolved-" + path
    monkeypatch.setattr("spacefit.config.shutil.which", which)
    settings = Settings(data_dir=tmp_path, openmvs_runtime="docker", docker=config())
    binaries = settings.executables()
    assert binaries["colmap"] == "resolved-fixture-colmap.exe"
    assert binaries["blender"] == "resolved-fixture-blender.exe"
    assert all(binaries[name] == "resolved-fixture-docker.exe" for name in MVS_TOOLS)
    assert calls.count("fixture-docker.exe") == 5
    assert settings.native_identity() == ("docker", config().identity(), "native", None, str(tmp_path.resolve()))


def test_native_mode_does_not_read_a_docker_config_file(monkeypatch, tmp_path):
    monkeypatch.setenv("SPACEFIT_DOCKER_CONFIG", str(tmp_path / "never-read-missing-config.json"))
    settings = Settings(data_dir=tmp_path, openmvs_runtime="native")
    assert settings.docker is None and settings.native_identity() == ("native", None, "native", None, None)


def pipeline_fixture(tmp_path, runtime="docker"):
    settings = Settings(data_dir=tmp_path, openmvs_runtime=runtime,
                        docker=config() if runtime == "docker" else None)
    if runtime == "docker":
        settings.executables = lambda: {name: "fixture-docker.exe" for name in MVS_TOOLS}
    store = JobStore(tmp_path)
    job_id = store.create({"name": "Docker command fixture"}, [], status="processing")["id"]
    root = tmp_path / job_id
    root.mkdir(exist_ok=True)
    work = root / "attempt-owned-fixture"
    work.mkdir()
    return Pipeline(store, settings), job_id, root, work


def test_pipeline_docker_run_mounts_only_the_current_job_attempt(monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    runs, cleanups = [], []
    monkeypatch.setattr(pipeline, "run", lambda *args: runs.append(args))
    def cleanup_reply(command, **kwargs):
        cleanups.append(command)
        return subprocess.CompletedProcess(command, 0, b"", b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", cleanup_reply)
    pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs", "--export-type", "obj"], work)
    assert len(runs) == len(cleanups) == 1
    actual_job, stage, progress, command, directory = runs[0]
    assert (actual_job, stage, progress, directory) == (job_id, "texture", 88, work)
    assert command[-5:] == [IMAGE, "TextureMesh", "/workspace/scene.mvs", "--export-type", "obj"]
    assert command[command.index("--mount") + 1] == f"type=bind,source={work.resolve()},target=/workspace"
    assert f"source={root.resolve()}," not in " ".join(command)
    assert f"source={tmp_path.resolve()}," not in " ".join(command)
    assert cleanups[0][-1] == command[command.index("--name") + 1]
    assert not (work / "model.glb").exists()


@pytest.mark.parametrize("directory", ["data", "job", "uploads", "other-job", "nested-attempt"])
def test_pipeline_rejects_mounts_outside_exact_top_level_attempt(directory, monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    candidates = {"data": tmp_path, "job": root, "uploads": root / "uploads",
                  "other-job": tmp_path / "other-job" / "attempt-owned-fixture",
                  "nested-attempt": work / "attempt-nested"}
    candidate = candidates[directory]
    candidate.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(pipeline, "run", lambda *args: pytest.fail("Unsafe workspace must not run"))
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *args, **kwargs: pytest.fail("Unsafe workspace must not clean unrelated containers"))
    with pytest.raises(ValueError, match="scoped to a job attempt"):
        pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs"], candidate)


def test_pipeline_native_openmvs_branch_is_unchanged(monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path, runtime="native")
    monkeypatch.setattr(pipeline.settings, "executables", lambda: {"TextureMesh": "resolved-native-texture.exe"})
    calls = []
    monkeypatch.setattr(pipeline, "run", lambda *args: calls.append(args))
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda *args, **kwargs: pytest.fail("Native branch must not invoke Docker"))
    pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs"], work)
    assert calls == [(job_id, "texture", 88, ["resolved-native-texture.exe", "scene.mvs"], work)]


@pytest.mark.parametrize("original", [RuntimeError("original stage failure"),
                                      TimeoutError("original stage timeout"), Cancelled("original cancellation")])
@pytest.mark.parametrize("cleanup_fails", [False, True])
def test_pipeline_failure_timeout_and_cancel_cleanup_without_masking_original(
        original, cleanup_fails, monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    runs, cleanups = [], []
    def stage_reply(*args):
        runs.append(args)
        raise original
    def cleanup_reply(command, **kwargs):
        cleanups.append(command)
        return subprocess.CompletedProcess(command, int(cleanup_fails), b"", b"private-cleanup-fixture" if cleanup_fails else b"")
    monkeypatch.setattr(pipeline, "run", stage_reply)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", cleanup_reply)
    with pytest.raises(type(original)) as seen:
        pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs"], work)
    assert seen.value is original and len(runs) == len(cleanups) == 1
    assert cleanups[0][-1] == runs[0][3][runs[0][3].index("--name") + 1]
    if cleanup_fails:
        log = (work / "pipeline.log").read_text(encoding="utf-8")
        assert "could not confirm owned container removal" in log
        assert "private-cleanup-fixture" not in log


def test_pipeline_cleanup_failure_after_success_is_not_silently_accepted(monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    monkeypatch.setattr(pipeline, "run", lambda *args: None)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda command, **kwargs:
                        subprocess.CompletedProcess(command, 1, b"", b"private-cleanup-fixture"))
    with pytest.raises(RuntimeError, match="owned container removal"):
        pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs"], work)
    assert "WARNING" in (work / "pipeline.log").read_text(encoding="utf-8")


def test_cleanup_warning_log_failure_cannot_mask_the_original_stage_error(monkeypatch, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    # Owned fixture reproduces an unwritable log path without changing ACLs.
    (work / "pipeline.log").mkdir()
    original = RuntimeError("original stage failure")
    def stage_failure(*args):
        raise original
    monkeypatch.setattr(pipeline, "run", stage_failure)
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", lambda command, **kwargs:
                        subprocess.CompletedProcess(command, 1, b"", b"private-cleanup-fixture"))
    with pytest.raises(RuntimeError) as seen:
        pipeline.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene.mvs"], work)
    assert seen.value is original


@pytest.mark.parametrize("shared_cache", [True, False])
@pytest.mark.parametrize("still_alive", [True, False])
def test_worker_close_waits_the_full_cleanup_budget_and_reports_an_unfinished_worker(
        shared_cache, still_alive, tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    pipeline.native = object() if shared_cache else None
    calls = []
    class ThreadContract:
        def join(self, timeout):
            assert pipeline.stop.is_set() and pipeline.wakeup.is_set()
            calls.append(timeout)

        def is_alive(self):
            return still_alive
    pipeline.thread = ThreadContract()
    if still_alive:
        with pytest.raises(RuntimeError, match="did not finish owned-process cleanup"):
            pipeline.close()
    else:
        pipeline.close()
    assert calls == [35 if shared_cache else 90]


def test_worker_close_does_not_return_before_owned_cleanup_thread_finishes(tmp_path):
    pipeline, job_id, root, work = pipeline_fixture(tmp_path)
    pipeline.native = object()
    cleanup_started, release, cleaned, close_returned = [threading.Event() for _ in range(4)]
    errors = []
    def own_cleanup():
        if not pipeline.stop.wait(2):
            errors.append("Worker did not receive stop")
            return
        cleanup_started.set()
        if not release.wait(2):
            errors.append("Test cleanup was not released")
            return
        cleaned.set()

    def close_owned_worker():
        try:
            pipeline.close()
        except BaseException as error:
            errors.append(error)
        finally:
            close_returned.set()
    pipeline.thread = threading.Thread(target=own_cleanup, name="owned-test-cleanup")
    closer = threading.Thread(target=close_owned_worker, name="owned-test-closer")
    try:
        pipeline.thread.start()
        closer.start()
        assert cleanup_started.wait(2)
        assert close_returned.wait(.02) is False
        release.set()
        closer.join(timeout=2)
        assert not closer.is_alive() and not pipeline.thread.is_alive()
        assert cleaned.is_set() and close_returned.is_set() and not errors
    finally:
        pipeline.stop.set()
        release.set()
        closer.join(timeout=2)
        pipeline.thread.join(timeout=2)


def completed(checker):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        result = checker.snapshot()
        if result["runtime"]["checked"]:
            return result
        time.sleep(.01)
    pytest.fail("Mocked Docker diagnostic batch did not finish")


def docker_settings(tmp_path):
    settings = Settings(data_dir=tmp_path, openmvs_runtime="docker", docker=config())
    settings.executables = lambda: {name: "fixture-" + name for name in TOOL_NAMES}
    return settings


@pytest.mark.parametrize("changes", [
    {"image": "sha256:" + "b" * 64}, {"context": "changed-context"},
    {"binary": "changed-docker.exe"}, {"threads": 2}, {"memory_mb": 2048},
])
def test_cached_native_success_is_invalidated_by_each_docker_config_change(changes, tmp_path):
    settings = docker_settings(tmp_path)
    original = settings.docker.identity()
    release = threading.Event()
    def probe(name, *args):
        if settings.docker.identity() == original:
            return dict(status="ready", reason="ok", exit=0)
        assert release.wait(5)
        return dict(status="error", reason="startup_failed", exit=125)
    checker = NativeReadiness(settings, probe=probe)
    try:
        assert completed(checker)["ready"] is True
        settings.docker = replace(settings.docker, **changes)
        pending = checker.snapshot()
        assert pending["ready"] is False and pending["runtime"]["checked"] is False
        assert pending["runtime"]["checking"] is True
        release.set()
        result = completed(checker)
        assert result["ready"] is False
        assert all(row["reason"] == "startup_failed" for row in result["runtime"]["tools"].values())
    finally:
        release.set()
        checker.close()


def test_inflight_old_image_success_cannot_publish_ready_for_the_new_image(tmp_path):
    settings = docker_settings(tmp_path)
    original = settings.docker.identity()
    release = threading.Event()
    all_started = threading.Barrier(len(TOOL_NAMES) + 1)
    def probe(name, *args):
        if settings.docker.identity() == original:
            all_started.wait(timeout=5)
            assert release.wait(5)
            return dict(status="ready", reason="ok", exit=0)
        return dict(status="error", reason="startup_failed", exit=125)
    checker = NativeReadiness(settings, probe=probe)
    try:
        assert checker.snapshot()["runtime"]["checking"] is True
        all_started.wait(timeout=5)
        settings.docker = replace(settings.docker, image="sha256:" + "c" * 64)
        assert checker.snapshot()["ready"] is False
        release.set()
        result = completed(checker)
        assert result["ready"] is False
        assert all(row["status"] == "error" for row in result["runtime"]["tools"].values())
    finally:
        release.set()
        checker.close()


@pytest.mark.parametrize("outcome,exit_code,output,reason", [
    ("help", 1, b"OpenMVS 2.4\nAvailable options: fixture", "ok"),
    ("daemon-failure", 125, b"fixture Docker daemon unavailable", "docker_start_failed"),
    ("wrong-help", 0, b"Docker version 28.0 fixture", "invalid_help"),
    ("timeout", None, b"", "timeout"),
])
def test_configured_docker_probe_checks_real_command_shape_and_cleans_every_outcome(
        monkeypatch, tmp_path, outcome, exit_code, output, reason):
    calls = []
    def docker_reply(command, **kwargs):
        calls.append((command, kwargs))
        if "run" in command:
            if outcome == "timeout":
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return subprocess.CompletedProcess(command, exit_code, output, b"")
        return subprocess.CompletedProcess(command, 0, b"", b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker_reply)
    settings = docker_settings(tmp_path)
    result = probe_configured_tool(settings, "TextureMesh", "resolved-fixture-docker.exe", tmp_path / "diagnostic", 5)
    assert result["reason"] == reason
    assert result["status"] == ("ready" if reason == "ok" else "error")
    assert len(calls) == 2
    run, kwargs = calls[0]
    cleanup, cleanup_kwargs = calls[1]
    assert run[0] == "resolved-fixture-docker.exe" and run[-3:] == [IMAGE, "TextureMesh", "-h"]
    assert kwargs.get("shell", False) is False and kwargs["timeout"] == 5
    assert cleanup[-1] == run[run.index("--name") + 1]
    assert cleanup[:6] == ["resolved-fixture-docker.exe", "--context", "desktop-linux", "container", "rm", "--force"]
    assert cleanup_kwargs.get("shell", False) is False


def test_successful_help_with_failed_container_cleanup_is_not_native_ready(monkeypatch, tmp_path):
    token = "private-cleanup-fixture-token"
    def docker_reply(command, **kwargs):
        if "run" in command:
            return subprocess.CompletedProcess(command, 0, b"OpenMVS 2.4\nAvailable options: fixture", b"")
        return subprocess.CompletedProcess(command, 1, b"", token.encode())
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", docker_reply)
    settings = docker_settings(tmp_path)
    result = probe_configured_tool(settings, "TextureMesh", "fixture-docker.exe", tmp_path, 5)
    assert result["status"] == "error" and result["reason"] == "cleanup_failed"
    assert token not in str(result)
    checker = NativeReadiness(settings, probe=lambda *args: result)
    try:
        state = completed(checker)
        assert state["ready"] is False
        assert all(row["reason"] == "cleanup_failed" for row in state["runtime"]["tools"].values())
        assert token not in str(state) and "detail" not in str(state)
    finally:
        checker.close()


@pytest.mark.parametrize("name", ["blender", "colmap"])
def test_colmap_and_blender_keep_their_native_commands_even_in_docker_mode(name, monkeypatch, tmp_path):
    calls = []
    def native_reply(command, **kwargs):
        calls.append(command)
        output = b"Blender 5.2 fixture" if name == "blender" else b"COLMAP 4.2 fixture\nUsage: colmap fixture"
        return subprocess.CompletedProcess(command, 0, output, b"")
    monkeypatch.setattr("spacefit.docker_runtime.subprocess.run", native_reply)
    path = "fixture-" + name
    result = probe_configured_tool(docker_settings(tmp_path), name, path, tmp_path, 5)
    assert result["status"] == "ready"
    assert calls == [[path, "--version" if name == "blender" else "-h"]]
