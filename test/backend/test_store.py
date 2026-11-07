from concurrent.futures import ThreadPoolExecutor
import pytest

from spacefit.store import JobStore


def test_single_transition_wins_under_concurrent_claims(tmp_path):
    store = JobStore(tmp_path)
    job = store.create({"name": "fixture"}, ["0000.jpg"])
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: store.transition(job["id"], {"queued"}, status="processing"), range(16)))
    assert sum(result is not None for result in results) == 1
    assert JobStore(tmp_path).get(job["id"])["status"] == "processing"


@pytest.mark.parametrize("status", ["processing", "calibrating", "uploading"])
def test_recovery_fails_only_interrupted_active_states(tmp_path, status):
    store = JobStore(tmp_path)
    job = store.create({"name": "fixture"}, ["0000.mp4"], status=status)
    store.update(job["id"], progress=58, calibration={"reference_cm": 15})
    reopened = JobStore(tmp_path)
    reopened.recover()
    result = reopened.get(job["id"])
    assert result["status"] == "failed" and result["stage"] == "interrupted"
    assert result["files"] == ["0000.mp4"] and result["progress"] == 58
    assert result["calibration"]["reference_cm"] == 15


@pytest.mark.parametrize("status", ["queued", "calibration_queued", "needs_review", "completed", "cancelled", "failed"])
def test_recovery_does_not_rewrite_idle_jobs(tmp_path, status):
    store = JobStore(tmp_path)
    job = store.create({"name": "fixture"}, [], status=status)
    store.recover()
    assert store.get(job["id"]) == job


def test_unknown_update_fails_and_transition_is_noop(tmp_path):
    store = JobStore(tmp_path)
    assert store.get("missing") is None
    assert store.transition("missing", {"queued"}, status="processing") is None
    with pytest.raises(KeyError):
        store.update("missing", status="completed")
    assert store.all() == []
