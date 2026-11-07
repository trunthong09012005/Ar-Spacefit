import cv2
import numpy as np
import pytest
from spacefit.config import Settings
from spacefit.images import prepare_images
from spacefit.pipeline import Cancelled


def test_unreadable_image_fails_without_generated_placeholder(tmp_path):
    source = tmp_path / "invalid.jpg"
    source.write_bytes(b"not an image")
    with pytest.raises(ValueError, match="Unreadable"):
        prepare_images([source], tmp_path / "output", Settings(min_images=1), lambda: None)
    assert not list((tmp_path / "output").glob("*.jpg"))


def test_small_image_rejected_and_input_untouched(tmp_path):
    source = tmp_path / "small.png"
    cv2.imwrite(str(source), np.zeros((319, 400, 3), dtype=np.uint8))
    original = source.read_bytes()
    with pytest.raises(ValueError, match="too small"):
        prepare_images([source], tmp_path / "output", Settings(min_images=1), lambda: None)
    assert source.read_bytes() == original


def test_large_image_is_downsampled_with_aspect_ratio(tmp_path):
    source = tmp_path / "large.png"
    image = np.random.default_rng(33).integers(0, 255, (1600, 3200, 3), dtype=np.uint8)
    cv2.imwrite(str(source), image)
    prepare_images([source], tmp_path / "output", Settings(min_images=1, blur_threshold=0), lambda: None)
    assert cv2.imread(str(tmp_path / "output" / "0000.jpg")).shape[:2] == (1200, 2400)


@pytest.mark.parametrize("frames,fps,opened", [(0, 30, True), (901, 1, True), (20, 0, True), (20, 30, False)])
def test_invalid_video_always_releases_decoder(tmp_path, monkeypatch, frames, fps, opened):
    class Video:
        released = False
        def get(self, key):
            return frames if key == cv2.CAP_PROP_FRAME_COUNT else fps
        def isOpened(self):
            return opened
        def release(self):
            self.released = True
    video = Video()
    monkeypatch.setattr(cv2, "VideoCapture", lambda _: video)
    with pytest.raises(ValueError, match="Video must"):
        prepare_images([tmp_path / "bad.mp4"], tmp_path / "output", Settings(), lambda: None)
    assert video.released


def test_cancelled_preparation_preserves_source_and_stops(tmp_path):
    source = tmp_path / "source.png"
    cv2.imwrite(str(source), np.random.default_rng(1).integers(0, 255, (320, 320, 3), dtype=np.uint8))
    original = source.read_bytes()
    def cancel():
        raise Cancelled()
    with pytest.raises(Cancelled):
        prepare_images([source], tmp_path / "output", Settings(), cancel)
    assert source.read_bytes() == original
    assert not list((tmp_path / "output").glob("*.jpg"))
