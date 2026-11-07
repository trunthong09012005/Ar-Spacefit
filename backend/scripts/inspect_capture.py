"""Inspect a local capture without changing the source or submitting a job."""
import argparse
import json
from pathlib import Path
import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from spacefit.config import Settings
from spacefit.images import prepare_images, sharpness


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New directory for diagnostic frames/results")
    parser.add_argument("--prepare", action="store_true", help="Also run production image-quality filtering")
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error("Video file not found")
    args.output.mkdir(parents=True, exist_ok=False)
    video = cv2.VideoCapture(str(args.video))
    report = dict(source=str(args.video.resolve()), size_bytes=args.video.stat().st_size, samples=[])
    try:
        if not video.isOpened():
            raise ValueError("Video cannot be decoded")
        total = int(video.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = video.get(cv2.CAP_PROP_FPS)
        if total <= 0 or fps <= 0:
            raise ValueError("Video has no readable frames/timing")
        report.update(frame_count=total, fps=fps, duration_seconds=total/fps,
                      width=int(video.get(cv2.CAP_PROP_FRAME_WIDTH)), height=int(video.get(cv2.CAP_PROP_FRAME_HEIGHT)))
        for sample, index in enumerate(np.linspace(0, total-1, min(total, 8), dtype=int)):
            video.set(cv2.CAP_PROP_POS_FRAMES, int(index))
            ok, frame = video.read()
            if not ok:
                continue
            path = args.output / f"sample-{sample:02d}.jpg"
            if not cv2.imwrite(str(path), frame, [cv2.IMWRITE_JPEG_QUALITY, 95]):
                raise OSError("Cannot save diagnostic frame")
            report["samples"].append(dict(frame=int(index), seconds=float(index/fps),
                                           sharpness=round(sharpness(frame), 2), path=str(path)))
    finally:
        video.release()
    if args.prepare:
        try:
            report["quality"] = prepare_images([args.video], args.output / "images", Settings(), lambda: None)
        except ValueError as error:
            report["quality_error"] = str(error)
    (args.output / "inspection.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
