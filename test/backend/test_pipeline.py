import sys

import cv2
import numpy as np
import pytest

from spacefit.config import Settings
from spacefit.images import prepare_images
from spacefit.pipeline import Pipeline, Cancelled
from spacefit.store import JobStore


def test_video_samples_both_ends_of_full_orbit(tmp_path, monkeypatch):
    """Regression: stopping after first 150 of 450 candidates lost 2/3 of orbit."""
    positions = []
    rng = np.random.default_rng(61)

    class Video:
        released = False

        def get(self, key):
            return 900 if key == cv2.CAP_PROP_FRAME_COUNT else 30

        def isOpened(self):
            return True

        def set(self, key, value):
            positions.append(value)

        def read(self):
            return True, rng.integers(0, 255, (320, 320, 3), dtype=np.uint8)

        def release(self):
            self.released = True

    video = Video()
    monkeypatch.setattr(cv2, "VideoCapture", lambda _: video)
    settings = Settings(data_dir=tmp_path, min_images=3, max_images=15)
    result = prepare_images([tmp_path / "orbit.mp4"], tmp_path / "images", settings, lambda: None)
    assert result["accepted_images"] == 15
    assert len(positions) == 15 and positions[0] == 0 and positions[-1] == 899
    assert video.released


@pytest.fixture
def runner(tmp_path):
    settings = Settings(data_dir=tmp_path, stage_timeout=1)
    store = JobStore(tmp_path)
    job_id = store.create({"name": "Test"}, [], status="processing")["id"]
    return Pipeline(store, settings), job_id, tmp_path


def test_native_log_is_retained_on_failure(runner):
    pipeline, job_id, work = runner
    command = [sys.executable, "-c", "from pathlib import Path; Path('native.log').write_text('mesh failed'); raise SystemExit(2)"]
    with pytest.raises(RuntimeError, match="exit 2"):
        pipeline.run(job_id, "mesh", 70, command, work)
    assert "mesh failed" in (work / "pipeline.log").read_text()


def test_stage_timeout_terminates_process(runner):
    pipeline, job_id, work = runner
    with pytest.raises(TimeoutError):
        pipeline.run(job_id, "test", 10, [sys.executable, "-c", "import time; time.sleep(30)"], work)


def test_cancelled_job_never_launches_command(runner):
    pipeline, job_id, work = runner
    pipeline.store.update(job_id, status="cancelled")
    with pytest.raises(Cancelled):
        pipeline.run(job_id, "test", 10, ["nonexistent-executable"], work)
    assert not (work / "pipeline.log").exists()


def test_immediate_retry_stops_previous_attempt(runner):
    pipeline, job_id, _ = runner
    pipeline.store.update(job_id, status="queued")
    with pytest.raises(Cancelled):
        pipeline.check(job_id)
