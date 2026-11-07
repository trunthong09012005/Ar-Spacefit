"""Real Docker/official OpenMVS on synthetic fixtures, not object-scan acceptance.

Opt-in only: Test-All passes the non-secret config path explicitly. Test artifacts
and SQLite are pytest-owned; no original capture, real job or application DB is used.
"""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import socket
import struct
import sys
import time

import cv2
import numpy as np
import pytest
import httpx

from spacefit.config import Settings
from spacefit.docker_runtime import DockerConfig, MVS_TOOLS, OpenMvsDocker
from spacefit.native_runtime import probe_configured_tool
from spacefit.pipeline import Pipeline, Cancelled
from spacefit.store import JobStore
from mvs_surface_fixture import surface_scene

CONFIG_PATH = os.getenv("SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG")
pytestmark = pytest.mark.skipif(not CONFIG_PATH, reason="Real Docker integration requires explicit test config")


@pytest.fixture
def docker_config():
    raw = json.loads(Path(CONFIG_PATH).read_text(encoding="utf-8-sig"))
    return DockerConfig(binary=raw["docker_bin"], image=raw["image"], context=raw["context"],
                        threads=raw["threads"], memory_mb=raw["memory_mb"])


def inspect(config, name):
    return subprocess.run([config.binary, "--context", config.context, "container", "inspect",
                           "--format", "{{.State.Running}}", name], capture_output=True, timeout=10)


def test_actual_workspace_preflight_uses_real_wsl_argv_without_restart(docker_config):
    if os.name != "nt":
        pytest.skip("Windows WSL workspace preflight is platform-specific")
    root = Path(__file__).resolve().parents[2]
    result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                             str(root / "tools/Ensure-OpenMvsDockerWorkspace.ps1")],
                            cwd=root, capture_output=True, text=True, timeout=45,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Docker workspace already available" in result.stdout or "Docker scoped workspace available" in result.stdout


@pytest.mark.parametrize("tool", MVS_TOOLS)
def test_actual_openmvs_help_loads_under_production_docker_constraints(tool, docker_config, docker_tmp_path):
    tmp_path = docker_tmp_path
    settings = Settings(data_dir=tmp_path, openmvs_runtime="docker", docker=docker_config)
    result = probe_configured_tool(settings, tool, docker_config.binary, tmp_path / tool, 60)
    assert result["status"] == "ready" and result["help_available"] is True
    assert result["exit"] in (0, 1) and "openmvs" in result["output"].lower()


def colmap_text_fixture(work):
    """Known cameras/eight cube points, with synthetic PNGs; not a captured object."""
    dense = work / "dense"
    sparse, images = dense / "sparse", dense / "images"
    sparse.mkdir(parents=True)
    images.mkdir()
    (sparse / "cameras.txt").write_text("1 PINHOLE 64 64 50 50 32 32\n", encoding="utf-8")
    points = [(i + 1, x, y, z) for i, (x, y, z) in enumerate(
        (x, y, z) for x in (-.5, .5) for y in (-.5, .5) for z in (3.5, 4.5))]
    image_lines = []
    for image_id, center_x in enumerate((-.5, 0, .5), start=1):
        name = f"fixture-{image_id}.png"
        pattern = np.zeros((64, 64, 3), dtype=np.uint8)
        pattern[::2, :, :] = (40, 160, 220)
        assert cv2.imwrite(str(images / name), pattern)
        image_lines.append(f"{image_id} 1 0 0 0 {-center_x} 0 0 1 {name}")
        image_lines.append(" ".join(f"{50*(x-center_x)/z+32} {50*y/z+32} {pid}" for pid, x, y, z in points))
    (sparse / "images.txt").write_text("\n".join(image_lines) + "\n", encoding="utf-8")
    (sparse / "points3D.txt").write_text("\n".join(
        f"{pid} {x} {y} {z} 40 160 220 0 " + " ".join(f"{image_id} {pid-1}" for image_id in (1, 2, 3))
        for pid, x, y, z in points) + "\n", encoding="utf-8")
    return dense


def pipeline_fixture(tmp_path, config, timeout=30):
    # Keep this isolated QA root short: the full gate adds group/test directories
    # and OpenMVS texture names can push Windows/OpenCV beyond MAX_PATH (260).
    # Never shorten/move a real job or reuse an existing pytest base directory.
    settings = Settings(data_dir=tmp_path / "d", openmvs_runtime="docker", docker=config, stage_timeout=timeout)
    store = JobStore(settings.data_dir)
    job = store.create({"name": "Docker integration synthetic fixture"}, [], status="processing")
    work = settings.data_dir / job["id"] / "attempt-fixture"
    work.mkdir(parents=True)
    return Pipeline(store, settings), job, work


def test_actual_interface_import_translates_host_paths_and_creates_real_mvs(docker_config, docker_tmp_path):
    tmp_path = docker_tmp_path
    pipeline, job, work = pipeline_fixture(tmp_path, docker_config)
    dense = colmap_text_fixture(work)
    pipeline.run_mvs(job["id"], "mvs_import", 53, "InterfaceCOLMAP",
                     ["-i", str(dense), "-o", "scene.mvs", "--image-folder", str(dense / "images")], work)
    scene = work / "scene.mvs"
    assert scene.is_file() and scene.stat().st_size > 100
    log = (work / "pipeline.log").read_text(encoding="utf-8", errors="replace").lower()
    assert "openmvs" in log and "3 images" in log and "8 points" in log
    assert not (work / "model.glb").exists()  # Import is not successful photogrammetry.


@pytest.mark.parametrize("densify", [False, True], ids=["known-cloud", "densified-cloud"])
def test_actual_mesh_refine_texture_handoff(docker_config, docker_tmp_path, densify):
    """Production stage chain on known synthetic cloud; no mocked native results."""
    pipeline, job, work = pipeline_fixture(docker_tmp_path, docker_config, timeout=120)
    dense = surface_scene(work)
    pipeline.run_mvs(job["id"], "mvs_import", 53, "InterfaceCOLMAP",
                     ["-i", str(dense), "-o", "scene.mvs" if densify else "scene_dense.mvs",
                      "--image-folder", str(dense / "images")], work)
    if densify:
        pipeline.densify_scene(job["id"], work)
    pipeline.reconstruct_mesh(job["id"], work)
    for name in ("scene_dense_mesh.mvs", "scene_dense_mesh_refine.mvs"):
        assert (work / name).stat().st_size > 100
    obj = work / "scene_dense_mesh_refine_texture.obj"
    content = obj.read_text(encoding="utf-8")
    assert "\nv " in content and "\nf " in content and "\nvt " in content
    material = next(work.glob("*texture*.mtl")).read_text(encoding="utf-8")
    textures = [line.split(maxsplit=1)[1] for line in material.splitlines() if line.startswith("map_Kd ")]
    assert textures
    for relative in textures:
        texture = cv2.imread(str(work / relative))
        assert texture is not None and float(texture.std()) > 5
    log = (work / "pipeline.log").read_text(encoding="utf-8", errors="replace")
    assert "Mesh refinement completed" in log and "Mesh texturing completed" in log
    assert not (work / "model.glb").exists()  # Surface integration is not final calibrated GLB.
    if densify:
        # Complete the native handoff into the real exporter. Known synthetic
        # cameras are still NOT COLMAP feature-matching or real-object acceptance.
        pipeline.store.update(job["id"], work_dir=work.name)
        blender = os.getenv("BLENDER_BIN", "D:/Blender/blender.exe" if os.name == "nt" else "blender")
        pipeline.export(pipeline.store.get(job["id"]), work.parent, blender, final=False)
        saved = pipeline.store.get(job["id"])
        assert saved["status"] == "needs_review" and saved["result"]["scale_confirmed"] is False
        assert saved["result"]["polygon_count"] > 0
        data = (work.parent / "preview.glb").read_bytes()
        assert data[:4] == b"glTF" and struct.unpack_from("<II", data, 4) == (2, len(data))
        json_size, chunk_type = struct.unpack_from("<II", data, 12)
        assert chunk_type == 0x4E4F534A
        gltf = json.loads(data[20:20+json_size])
        assert gltf["meshes"] and gltf["materials"] and gltf["images"]
        assert all("bufferView" in image for image in gltf["images"])
        assert not (work.parent / "model.glb").exists()  # Never silently confirm scale.


def test_actual_native_failure_keeps_pipeline_log_and_owned_attempt(docker_config, docker_tmp_path):
    tmp_path = docker_tmp_path
    pipeline, job, work = pipeline_fixture(tmp_path, docker_config)
    marker = work / "preserved-fixture.txt"
    marker.write_text("owned test input", encoding="utf-8")
    with pytest.raises(RuntimeError, match=r"failed \(exit"):
        pipeline.run_mvs(job["id"], "dense_cloud", 58, "DensifyPointCloud",
                         ["missing-scene.mvs", "--resolution-level", "1", "--max-threads", "4"], work)
    log = (work / "pipeline.log").read_text(encoding="utf-8", errors="replace").lower()
    assert "openmvs" in log and "missing-scene.mvs" in log
    assert marker.read_text(encoding="utf-8") == "owned test input"
    assert not (work / "model.glb").exists()


@pytest.mark.parametrize("ending", ["timeout", "cancel"])
def test_actual_daemon_container_is_removed_after_timeout_or_cancel(ending, docker_config, docker_tmp_path):
    tmp_path = docker_tmp_path
    pipeline, job, work = pipeline_fixture(tmp_path, docker_config, timeout=4 if ending == "timeout" else 30)
    prepared = OpenMvsDocker(docker_config).prepare("TextureMesh", ["-h"], work)
    # Infrastructure fixture ONLY: the public provider cannot run arbitrary programs.
    # An Ubuntu sleep process makes daemon-side cancellation observable and bounded.
    command = prepared.argv[:prepared.argv.index(docker_config.image) + 1] + ["sleep", "30"]
    def run():
        with prepared:
            pipeline.run(job["id"], "docker_cleanup_fixture", 1, command, work)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(run)
            deadline = time.monotonic() + 3
            running = False
            while time.monotonic() < deadline:
                response = inspect(docker_config, prepared.container_name)
                if response.returncode == 0 and response.stdout.strip() == b"true":
                    running = True
                    break
                time.sleep(.05)
            assert running, "Actual daemon-side fixture did not enter running state"
            if ending == "cancel":
                pipeline.store.update(job["id"], status="cancelled", stage="cancelled")
            with pytest.raises(Cancelled if ending == "cancel" else TimeoutError):
                future.result(timeout=20)
        assert prepared.cleanup_error is None
        assert inspect(docker_config, prepared.container_name).returncode != 0
        assert work.is_dir() and (work / "pipeline.log").is_file()
    finally:
        prepared.cleanup()


@pytest.mark.parametrize("ending", ["oom", "kill"])
def test_actual_bounded_exit137_evidence_cleanup_and_public_http(ending, docker_config, docker_tmp_path, monkeypatch):
    """Real negative infrastructure fixture, NOT a successful reconstruction.

    256 MiB hard memory + no swap bounds the deliberate allocation failure.
    Only fixture prepare() may substitute shell/coreutils for the native tool.
    Public production argv validation continues to reject arbitrary programs.
    """
    config = replace(docker_config, memory_mb=256, threads=1)
    pipeline, job, work = pipeline_fixture(docker_tmp_path, config, timeout=25)
    marker = work / "original-fixture.txt"
    marker.write_text("preserved input fixture", encoding="utf-8")
    prepared = OpenMvsDocker(config).prepare("RefineMesh", ["-h"], work)
    position = prepared.argv.index(config.image)
    # PID 1 has special signal semantics: kill a child and propagate wait's
    # status instead of assuming a shell can kill itself as container init.
    script = ("head -c 536870912 /dev/zero | tail -c 536870912" if ending == "oom" else
              'sleep 30 & child=$!; kill -9 "$child"; wait "$child"')
    prepared.argv = prepared.argv[:position] + ["--memory-swap", "256m", config.image, "sh", "-c", script]
    monkeypatch.setattr(OpenMvsDocker, "prepare", lambda *args: prepared)
    prefix = "NATIVE_OUT_OF_MEMORY:" if ending == "oom" else "NATIVE_KILLED:"
    try:
        with pytest.raises(RuntimeError, match="^" + prefix) as caught:
            pipeline.run_mvs(job["id"], "refine", 80, "RefineMesh", ["scene.mvs"], work)
        assert prepared.cleanup_error is None
        assert inspect(config, prepared.container_name).returncode != 0
        assert marker.read_text(encoding="utf-8") == "preserved input fixture"
        assert not (work / "model.glb").exists()
        # Persist the observed real negative result as a FAILED HTTP fixture;
        # do not fake native success or bypass readiness on a public upload.
        pipeline.store.update(job["id"], status="failed", error=str(caught.value))
        token = "oom-public-http-fixture-token"
        env = dict(os.environ, SPACEFIT_TOKEN=token, SPACEFIT_DATA_DIR=str(pipeline.settings.data_dir),
                   SPACEFIT_OPENMVS_RUNTIME="native", SPACEFIT_COLMAP_RUNTIME="native")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        with (work / "fixture-http.log").open("wb") as output:
            server = subprocess.Popen([sys.executable, "-B", "-m", "uvicorn", "spacefit.api:create_app",
                                       "--factory", "--host", "127.0.0.1", "--port", str(port)],
                cwd=Path(__file__).resolve().parents[2] / "backend", env=env, stdout=output, stderr=output,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=3, trust_env=False,
                                  headers={"Authorization": "Bearer " + token}) as client:
                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        assert server.poll() is None
                        try:
                            if client.get("/health").status_code == 200:
                                break
                        except httpx.TransportError:
                            pass
                        time.sleep(.1)
                    route = "/jobs/" + job["id"]
                    for _ in range(3):
                        response = client.get(route)
                        assert response.status_code == 200
                        status = response.json()
                        assert status["status"] == "failed" and status["stage"] == "refine"
                        assert status["error"].startswith(prefix) and "256 MiB" in status["error"]
                        assert "work_dir" not in status and "files" not in status
                    assert client.get(route + "/artifacts/model").status_code == 404
                    assert client.get(route, headers={"Authorization": ""}).status_code == 401
                    assert client.post(route + "/cancel").status_code == 409
            finally:
                if server.poll() is None:
                    server.terminate()
                    try:
                        server.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait(timeout=5)
    finally:
        prepared.cleanup()


@pytest.mark.parametrize("image_available", [True, False])
def test_actual_docker_readiness_over_public_http_without_uploading_a_job(docker_config, docker_tmp_path, image_available):
    """Real Uvicorn/TCP + native startup, never a reconstruction success fixture."""
    tmp_path = docker_tmp_path
    backend_root = Path(__file__).resolve().parents[2] / "backend"
    token = "docker-http-fixture-only-token"
    colmap_default = str(backend_root.parent / ".tools/colmap-4.2.0/bin/colmap.exe") if os.name == "nt" else "colmap"
    env = dict(os.environ, SPACEFIT_TOKEN=token, SPACEFIT_DATA_DIR=str(tmp_path / "isolated-http-data"),
               SPACEFIT_OPENMVS_RUNTIME="docker", SPACEFIT_DOCKER_CONFIG=CONFIG_PATH,
               COLMAP_BIN=os.getenv("COLMAP_BIN", colmap_default),
               BLENDER_BIN=os.getenv("BLENDER_BIN", "D:/Blender/blender.exe" if os.name == "nt" else "blender"))
    if not image_available:
        # Real negative Docker request, --pull never: no downloads, fake native
        # binaries, successful-output stubs, or access to the production server.
        raw = json.loads(Path(CONFIG_PATH).read_text(encoding="utf-8-sig"))
        raw["image"] = "sha256:" + "f" * 64
        missing_config = tmp_path / "missing-image-fixture.json"
        missing_config.write_text(json.dumps(raw), encoding="utf-8")
        env["SPACEFIT_DOCKER_CONFIG"] = str(missing_config)
    if os.getenv("SPACEFIT_COLMAP_DOCKER_TEST_CONFIG"):
        env.update(SPACEFIT_COLMAP_RUNTIME="docker", SPACEFIT_COLMAP_DOCKER_CONFIG=os.environ["SPACEFIT_COLMAP_DOCKER_TEST_CONFIG"])
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    with (tmp_path / "http-server.log").open("wb") as output:
        process = subprocess.Popen([sys.executable, "-B", "-m", "uvicorn", "spacefit.api:create_app",
                                    "--factory", "--host", "127.0.0.1", "--port", str(port), "--workers", "1"],
                                   cwd=backend_root, env=env, stdout=output, stderr=output,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=3,
                              headers={"Authorization": "Bearer " + token}) as client:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    assert process.poll() is None, "Owned Docker HTTP server exited"
                    try:
                        if client.get("/health").status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    time.sleep(.1)
                assert client.get("/health").json()["status"] == "ok"
                assert client.get("/capabilities", headers={"Authorization": ""}).status_code == 401
                deadline = time.monotonic() + 80
                while time.monotonic() < deadline:
                    response = client.get("/capabilities")
                    assert response.status_code == 200
                    result = response.json()
                    if result["runtime"]["checked"]:
                        break
                    assert result["ready"] is False
                    time.sleep(.1)
                (tmp_path / "capabilities-real-docker.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
                assert result["runtime"]["checked"] is True
                if image_available:
                    assert result["ready"] is True
                    assert all(row["status"] == "ready" for row in result["runtime"]["tools"].values())
                else:
                    assert result["ready"] is False
                    for name in MVS_TOOLS:
                        row = result["runtime"]["tools"][name]
                        assert row["status"] == "error" and row["reason"] == "docker_start_failed"
                        assert row["exit"] == 125 and row["hex_exit"] == "0x7d"
                    assert "f" * 64 not in response.text
                assert len(result["runtime"]["tools"]) == 7
                assert token not in response.text and docker_config.image not in response.text
                assert str(tmp_path) not in response.text and "output" not in response.text
                assert client.get("/jobs").json() == []
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
