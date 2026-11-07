"""Actual Uvicorn process + TCP HTTP, public contract only. No production DB/native tools.

This verifies failure handling/retry/persistence, NOT successful photogrammetry.
"""
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import sys
import time
from uuid import uuid4
import httpx
import pytest


@pytest.fixture
def server(tmp_path, request):
    token = "blackbox-only-" + uuid4().hex
    env = dict(os.environ, SPACEFIT_OPENMVS_RUNTIME="native", SPACEFIT_DATA_DIR=str(tmp_path / "data"), SPACEFIT_TOKEN=token,
               SPACEFIT_MIN_IMAGES="3", SPACEFIT_MAX_UPLOAD_MB="1",
               COLMAP_BIN=sys.executable, BLENDER_BIN=str(tmp_path / "no-blender"),
               OPENMVS_BIN_DIR=str(tmp_path / "no-openmvs"), PYTHONUNBUFFERED="1")
    if getattr(request, "param", "missing") == "all-wrong":
        # Negative executable fixtures: real Python copies, never native-success stubs.
        tools = tmp_path / "wrong-tools"
        tools.mkdir()
        for name in ("colmap", "blender", "InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh"):
            shutil.copy2(sys.executable, tools / (name + (".exe" if os.name == "nt" else "")))
        env.update(COLMAP_BIN=str(tools / ("colmap.exe" if os.name == "nt" else "colmap")),
                   BLENDER_BIN=str(tools / ("blender.exe" if os.name == "nt" else "blender")), OPENMVS_BIN_DIR=str(tools))
    client = None
    process = None
    output = (tmp_path / "server.log").open("wb")

    def start():
        nonlocal client, process
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        process = subprocess.Popen([sys.executable, "-m", "uvicorn", "spacefit.api:create_app", "--factory",
                                    "--host", "127.0.0.1", "--port", str(port)],
            cwd=Path(__file__).resolve().parents[2] / "backend", env=env, stdout=output, stderr=output,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        client = httpx.Client(base_url=f"http://127.0.0.1:{port}", headers={"Authorization": "Bearer " + token},
                              timeout=5, trust_env=False)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if process.poll() is not None:
                pytest.fail("Isolated test server exited; inspect " + str(tmp_path / "server.log"))
            try:
                if client.get("/health").status_code == 200:
                    return client
            except httpx.TransportError:
                pass
            time.sleep(.1)
        pytest.fail("Isolated server did not start within 20 seconds")

    def stop():
        nonlocal process, client
        if client is not None:
            client.close()
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    try:
        yield start(), lambda: (stop(), start())[1]
    finally:
        stop()
        output.close()


def test_public_health_auth_validation_and_size_over_real_http(server):
    client, _ = server
    assert client.get("/health", headers={"Authorization": ""}).json()["status"] == "ok"
    assert client.get("/jobs", headers={"Authorization": ""}).status_code == 401
    capabilities = client.get("/capabilities").json()
    assert capabilities["ready"] is False
    assert client.post("/jobs", data={"metadata": "{}"}, files={"files": ("x.mp4", b"x")}).status_code == 422
    assert client.post("/jobs", data={"metadata": '{"name":"x","reference_cm":15}'},
                       files={"files": ("big.mp4", b"x" * (1024 * 1024 + 1))}).status_code == 413
    assert client.get("/jobs").json() == []


def test_existing_wrong_executable_is_not_ready_over_real_http(server):
    client, _ = server
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        response = client.get("/capabilities")
        assert response.status_code == 200
        result = response.json()
        assert result["ready"] is False
        if result["runtime"]["checked"]:
            break
        time.sleep(.05)
    assert result["tools"]["colmap"] is True  # python.exe exists, but is NOT COLMAP.
    assert result["runtime"]["checked"] is True
    assert result["runtime"]["tools"]["colmap"]["reason"] == "invalid_help"
    assert result["runtime"]["tools"]["colmap"]["status"] == "error"
    assert "output" not in response.text and "private" not in response.text
    assert client.get("/jobs").json() == []


@pytest.mark.parametrize("server", ["all-wrong"], indirect=True)
def test_native_startup_failure_is_reported_before_reconstruction_over_real_http(server):
    client, _ = server
    assert all(client.get("/capabilities").json()["tools"].values())
    response = client.post("/jobs", data={"metadata": json.dumps({"name": "native failure HTTP fixture", "reference_cm": 15})},
                           files={"files": ("capture.mp4", b"preserved invalid fixture video")})
    assert response.status_code == 202
    route = "/jobs/" + response.json()["id"]
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        result = client.get(route).json()
        if result["status"] == "failed":
            break
        time.sleep(.05)
    assert result["status"] == "failed" and result["stage"] == "checking_tools"
    assert "Native tools failed startup" in result["error"]
    assert result["metrics"] == {} and result["progress"] == 0
    assert client.get(route + "/artifacts/model").status_code == 404
    assert client.get(route + "/artifacts/log").status_code == 404
    assert client.post(route + "/retry").status_code == 200


def test_upload_failure_retry_idempotency_and_restart_via_public_api(server):
    client, restart = server
    key = str(uuid4())
    def upload(client):
        return client.post("/jobs", headers={"X-Request-ID": key},
            data={"metadata": json.dumps({"name": "Ghế kiểm thử HTTP", "reference_cm": 15})},
            files={"files": ("../../capture.mp4", b"invalid test video")})
    response = upload(client)
    assert response.status_code == 202
    job_id = response.json()["id"]
    route = f"/jobs/{job_id}"
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        result = client.get(route).json()
        if result["status"] == "failed":
            break
        time.sleep(.1)
    assert result["status"] == "failed" and "Missing tools" in result["error"]
    assert "files" not in result and "work_dir" not in result
    assert client.get(route + "/artifacts/model").status_code == 404
    assert upload(client).json()["id"] == job_id
    assert len(client.get("/jobs").json()) == 1
    assert client.post(route + "/retry").status_code == 200
    client = restart()
    assert client.get(route).json()["metadata"]["name"] == "Ghế kiểm thử HTTP"
    assert len(client.get("/jobs").json()) == 1
    assert client.get(route + "/artifacts/model").status_code == 404
