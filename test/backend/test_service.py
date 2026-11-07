import json
from uuid import uuid4
import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from spacefit.api import create_app
from spacefit.config import Settings
from spacefit.images import prepare_images
from spacefit.pipeline import Pipeline
from spacefit.store import JobStore

TOKEN = "test-token-at-least-sixteen-chars"


@pytest.fixture
def service(tmp_path):
    config = Settings(data_dir=tmp_path, token=TOKEN, min_images=3)
    app = create_app(config, start_worker=False)
    with TestClient(app, headers={"Authorization": "Bearer " + TOKEN}) as client:
        yield client, app, config


def payload():
    return {"metadata": json.dumps(dict(name="Ghế thử", reference_cm=68))}


def photos():
    rng = np.random.default_rng(42)
    return [("files", (f"../../chair-{i}.jpg", cv2.imencode(".jpg", rng.integers(0, 255, (400, 400, 3), dtype=np.uint8))[1].tobytes(), "image/jpeg")) for i in range(3)]


def upload(client):
    response = client.post("/jobs", data=payload(), files=photos())
    assert response.status_code == 202, response.text
    return response.json()


def test_health_auth_and_missing_token(service, tmp_path):
    client, _, _ = service
    assert client.get("/health", headers={"Authorization": "bad"}).status_code == 200
    assert client.get("/jobs", headers={"Authorization": "bad"}).status_code == 401
    with TestClient(create_app(Settings(data_dir=tmp_path / "other", token=""), False)) as other:
        assert other.get("/jobs").status_code == 503


def test_upload_state_and_safe_filenames(service):
    client, app, settings = service
    job = upload(client)
    root = settings.data_dir / job["id"] / "uploads"
    assert sorted(file.name for file in root.iterdir()) == ["0000.jpg", "0001.jpg", "0002.jpg"]
    assert job["status"] == "queued" and "files" not in job
    assert client.get(f"/jobs/{job['id']}").json()["metadata"]["reference_cm"] == 68
    assert client.get(f"/jobs/{job['id']}/artifacts/model").status_code == 404
    assert client.post(f"/jobs/{job['id']}/cancel").json()["status"] == "cancelled"
    assert client.post(f"/jobs/{job['id']}/retry").json()["status"] == "queued"
    assert client.post(f"/jobs/{job['id']}/retry").status_code == 409


def test_idempotent_upload(service):
    client, _, _ = service
    key = str(uuid4())
    first = client.post("/jobs", headers={"X-Request-ID": key}, data=payload(), files=photos())
    again = client.post("/jobs", headers={"X-Request-ID": key}, data=payload(), files=photos())
    assert first.json()["id"] == again.json()["id"]
    assert len(client.get("/jobs").json()) == 1


@pytest.mark.parametrize("metadata", [dict(name="x", reference_cm=0), dict(name="x", reference_cm=float('inf')), dict(name="x", reference_cm=60, command="echo anything")])
def test_invalid_metadata(service, metadata):
    client, _, settings = service
    response = client.post("/jobs", data={"metadata": json.dumps(metadata)}, files=photos())
    assert response.status_code == 422
    assert not list(settings.data_dir.glob("incoming-*"))


def test_reject_short_scan_and_unsupported_file(service):
    client, _, _ = service
    assert client.post("/jobs", data=payload(), files=photos()[:1]).status_code == 422
    assert client.post("/jobs", data=payload(), files=[("files", ("a.exe", b"x"))]).status_code == 422


def test_calibration_requires_review_and_valid_bounds(service):
    client, app, settings = service
    job = upload(client)
    url = f"/jobs/{job['id']}/calibrate"
    assert client.post(url, json=dict(reference_cm=68)).status_code == 422
    assert client.post(url, json=dict(reference_cm=68, reviewed=True)).status_code == 409
    app.state.store.update(job["id"], status="needs_review", work_dir="attempt-test")
    (settings.data_dir / job["id"] / "preview.glb").write_bytes(b"glTF-test-fixture")
    assert client.post(url, json=dict(reference_cm=68, reviewed=True, crop_min=[0.5, 0, 0], crop_max=[0.2, 1, 1])).status_code == 422
    assert client.post(url, json=dict(reference_cm=68, reviewed=True)).json()["status"] == "calibration_queued"


def test_restart_recovery_preserves_data_and_retry(service):
    client, app, settings = service
    job = upload(client)
    app.state.store.update(job["id"], status="processing", progress=25)
    reopened = JobStore(settings.data_dir)
    reopened.recover()
    restored = reopened.get(job["id"])
    assert restored["status"] == "failed" and restored["progress"] == 25
    assert (settings.data_dir / job["id"] / "uploads" / "0000.jpg").exists()
    assert client.post(f"/jobs/{job['id']}/retry").status_code == 200


def test_missing_real_tools_fail_honestly(service, monkeypatch):
    client, app, settings = service
    monkeypatch.setattr(settings, "executables", lambda: {"colmap": None, "blender": None})
    job = upload(client)
    Pipeline(app.state.store, settings).process(app.state.store.get(job["id"]))
    updated = client.get(f"/jobs/{job['id']}").json()
    assert updated["status"] == "failed" and "Missing tools" in updated["error"]
    assert not (settings.data_dir / job["id"] / "model.glb").exists()


def test_frame_validation_drops_blur_and_duplicates(tmp_path):
    rng = np.random.default_rng(10)
    sharp = rng.integers(0, 255, (400, 400, 3), dtype=np.uint8)
    inputs = []
    for i, image in enumerate([sharp, sharp, np.zeros_like(sharp)]):
        path = tmp_path / f"source-{i}.jpg"
        cv2.imwrite(str(path), image)
        inputs.append(path)
    settings = Settings(data_dir=tmp_path, min_images=1)
    result = prepare_images(inputs, tmp_path / "images", settings, lambda: None)
    assert result == dict(accepted_images=1, blurry_images=1, duplicate_images=1)
    settings.min_images = 3
    with pytest.raises(ValueError, match="Only 1 usable"):
        prepare_images(inputs, tmp_path / "another", settings, lambda: None)


def test_upload_size_is_bounded(service):
    client, _, settings = service
    settings.max_upload_bytes = 10
    assert client.post("/jobs", data=payload(), files=photos()).status_code == 413


def test_unknown_job_and_artifact_paths(service):
    client, _, _ = service
    assert client.get("/jobs/not-a-uuid").status_code == 404
    assert client.get(f"/jobs/{uuid4()}/artifacts/model").status_code == 404
    job = upload(client)
    assert client.get(f"/jobs/{job['id']}/artifacts/database.db").status_code == 404
