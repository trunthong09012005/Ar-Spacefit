import hashlib
from pathlib import Path
import cv2
import numpy as np


def sharpness(image: np.ndarray) -> float:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    if max(gray.shape) > 640:
        gray = cv2.resize(gray, (round(gray.shape[1] * 640 / max(gray.shape)), round(gray.shape[0] * 640 / max(gray.shape))))
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def prepare_images(sources: list[Path], output: Path, settings, check_cancel) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    accepted, blurry, duplicates = 0, 0, 0
    hashes: set[str] = set()

    def accept(image):
        nonlocal accepted, blurry, duplicates
        check_cancel()
        if image is None or min(image.shape[:2]) < 320 or image.size > 80_000_000:
            raise ValueError("Unreadable image, image too small (<320 px), or image too large")
        if sharpness(image) < settings.blur_threshold:
            blurry += 1
            return
        digest = hashlib.sha256(cv2.resize(image, (64, 64)).tobytes()).hexdigest()
        if digest in hashes:
            duplicates += 1
            return
        hashes.add(digest)
        if max(image.shape[:2]) > 2400:
            scale = 2400 / max(image.shape[:2])
            image = cv2.resize(image, (round(image.shape[1] * scale), round(image.shape[0] * scale)))
        if accepted < settings.max_images:
            if not cv2.imwrite(str(output / f"{accepted:04d}.jpg"), image, [cv2.IMWRITE_JPEG_QUALITY, 95]):
                raise ValueError("Cannot save image")
            accepted += 1

    if len(sources) == 1 and sources[0].suffix.lower() in {".mp4", ".mov", ".mkv", ".webm"}:
        video = cv2.VideoCapture(str(sources[0]))
        try:
            total = int(video.get(cv2.CAP_PROP_FRAME_COUNT))
            fps = video.get(cv2.CAP_PROP_FPS)
            if not video.isOpened() or total <= 0 or fps <= 0 or total / fps > 600:
                raise ValueError("Video must be readable and no longer than 10 minutes")
            # Sample the ENTIRE orbit, not just the first third of a sharp video.
            for index in np.linspace(0, total - 1, min(total, settings.max_images), dtype=int):
                check_cancel()
                video.set(cv2.CAP_PROP_POS_FRAMES, int(index))
                ok, frame = video.read()
                if ok:
                    accept(frame)
                if accepted >= settings.max_images:
                    break
        finally:
            video.release()
    else:
        for source in sources:
            # imdecode also works with Vietnamese filenames on Windows.
            accept(cv2.imdecode(np.fromfile(source, dtype=np.uint8), cv2.IMREAD_COLOR))
    if accepted < settings.min_images:
        raise ValueError(f"Only {accepted} usable images; need {settings.min_images}. Rejected blurry: {blurry}; duplicate: {duplicates}. Capture more overlapping views with steady lighting.")
    return dict(accepted_images=accepted, blurry_images=blurry, duplicate_images=duplicates)
