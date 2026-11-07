"""HTTP contract integration (in-process transport, isolated real SQLite)."""
import json
from uuid import uuid4
import pytest
from fastapi.testclient import TestClient
from spacefit.api import create_app
from spacefit.config import Settings

TOKEN = "isolated-contract-test-token"


@pytest.fixture
def service(tmp_path):
    app = create_app(Settings(data_dir=tmp_path, token=TOKEN, min_images=1), start_worker=False)
    with TestClient(app, headers={"Authorization": "Bearer " + TOKEN}) as client:
        yield client, app, tmp_path


def post(client, **kwargs):
    return client.post("/jobs", data={"metadata": json.dumps({"name": "Fixture", "reference_cm": 15})},
                       files={"files": ("../../fixture.mp4", b"deliberately-invalid-video", "video/mp4")}, **kwargs)


@pytest.mark.parametrize("method,path", [("get", "/jobs"), ("post", "/jobs"), ("get", "/capabilities"),
    ("get", "/jobs/not-a-uuid"), ("post", "/jobs/not-a-uuid/cancel"),
    ("post", "/jobs/not-a-uuid/retry"), ("post", "/jobs/not-a-uuid/calibrate"),
    ("get", "/jobs/not-a-uuid/artifacts/log")])
def test_all_private_endpoints_require_pairing_before_work(service, method, path):
    client, _, _ = service
    assert getattr(client, method)(path, headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/jobs").json() == []


def test_request_id_is_normalized_for_idempotency(service):
    client, _, _ = service
    request_id = str(uuid4())
    first = post(client, headers={"X-Request-ID": request_id})
    second = post(client, headers={"X-Request-ID": request_id.upper()})
    assert first.status_code == second.status_code == 202
    assert first.json()["id"] == second.json()["id"]
    assert len(client.get("/jobs").json()) == 1


@pytest.mark.parametrize("headers,expected", [({"X-Request-ID": "../not-a-uuid"}, 422),
    ({"Content-Length": "invalid"}, 400), ({"Content-Length": "-1"}, 400)])
def test_bad_request_headers_leave_no_jobs_or_staging(service, headers, expected):
    client, _, root = service
    assert post(client, headers=headers).status_code == expected
    assert client.get("/jobs").json() == []
    assert not list(root.glob("incoming-*"))


def test_empty_file_and_malformed_multipart_are_cleaned_up(service):
    client, _, root = service
    response = client.post("/jobs", data={"metadata": '{"name":"x","reference_cm":15}'},
                           files={"files": ("empty.mp4", b"")})
    assert response.status_code == 422
    assert client.post("/jobs", content=b"garbage", headers={"Content-Type": "multipart/form-data"}).status_code == 400
    assert client.get("/jobs").json() == []
    assert not list(root.glob("incoming-*"))


def test_cancel_retry_conflicts_and_no_internal_paths(service):
    client, _, _ = service
    job = post(client).json()
    assert not {"files", "work_dir"} & job.keys()
    route = f"/jobs/{job['id']}"
    assert client.post(route + "/retry").status_code == 409
    assert client.post(route + "/cancel").json()["status"] == "cancelled"
    assert client.post(route + "/cancel").status_code == 409
    assert client.post(route + "/retry").json()["status"] == "queued"
    assert client.get(route + "/artifacts/model").status_code == 404


def test_cancelled_calibration_can_still_download_preview(service):
    client, app, root = service
    job = post(client).json()
    # Fixture setup is integration, not an end-to-end reconstruction claim.
    (root / job["id"] / "preview.glb").write_bytes(b"preview-fixture")
    app.state.store.update(job["id"], status="needs_review", work_dir="attempt-fixture")
    route = f"/jobs/{job['id']}"
    assert client.post(route + "/calibrate", json={"reference_cm": 15, "reviewed": True}).status_code == 200
    assert client.post(route + "/cancel").status_code == 200
    preview = client.get(route + "/artifacts/preview")
    assert preview.status_code == 200 and preview.content == b"preview-fixture"
    assert client.get(route + "/artifacts/model").status_code == 404
