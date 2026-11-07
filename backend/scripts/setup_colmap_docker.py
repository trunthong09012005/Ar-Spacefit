"""Build/verify a scoped CPU image, then atomically publish non-secret config.

Owner approval is required before invoking this installer. It never edits .env,
restarts Docker/WSL, changes Windows protection or runs on a user's capture.
"""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from spacefit.docker_runtime import ColmapDocker, DockerConfig


def invoke(argv, *, timeout=120, cwd=None):
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd,
                          creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)


def verified_command(provider, args, directory, log_name, timeout=120):
    prepared = provider.prepare("colmap", args, directory)
    with prepared:
        result = invoke(prepared.argv, timeout=timeout, cwd=directory)
        (directory / log_name).write_text(result.stdout + result.stderr, encoding="utf-8")
    if prepared.cleanup_error:
        raise RuntimeError(prepared.cleanup_error)
    if result.returncode:
        raise RuntimeError(f"COLMAP verification failed; see {directory / log_name}")
    return result.stdout + result.stderr


def setup(root, binary, context="desktop-linux", threads=4, memory_mb=4096, force=False):
    destination = root / "backend/native-colmap-docker.json"
    if destination.exists() and not force:
        raise ValueError("COLMAP config already exists; use --force-update only after reviewing it")
    # Validate bounds/context before any Docker/WSL/build side effect.
    DockerConfig(binary=binary, image="sha256:" + "0" * 64, context=context, threads=threads, memory_mb=memory_mb)
    if not shutil.which(binary):
        raise ValueError("Docker CLI not found")
    info = invoke([binary, "--context", context, "info", "--format", "{{.OSType}}"])
    if info.returncode or info.stdout.strip() != "linux":
        raise RuntimeError("A running Linux Docker daemon is required")
    if context == "desktop-linux" and os.name == "nt":
        preflight = invoke(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            str(root / "tools/Ensure-OpenMvsDockerWorkspace.ps1")], timeout=60, cwd=root)
        if preflight.returncode:
            raise RuntimeError("Docker workspace preflight failed; config was not changed")
    run = root / ".tools/qa" / ("colmap-setup-" + uuid4().hex)
    context_dir = run / "build-context"
    context_dir.mkdir(parents=True)
    shutil.copyfile(root / "infra/docker/colmap/Dockerfile", context_dir / "Dockerfile")
    print(f"COLMAP build/verification evidence: {run}", flush=True)
    tag = "ar-spacefit/colmap:3.9.1-cpu"
    build = invoke([binary, "--context", context, "build", "--platform", "linux/amd64",
                    "--progress", "plain", "--tag", tag, str(context_dir)], timeout=1800, cwd=root)
    (run / "build.log").write_text(build.stdout + build.stderr, encoding="utf-8")
    if build.returncode:
        raise RuntimeError(f"COLMAP Docker build failed; see {run / 'build.log'}")
    inspected = invoke([binary, "--context", context, "image", "inspect", "--format", "{{.Id}}", tag])
    if inspected.returncode:
        raise RuntimeError("Could not resolve COLMAP image ID")
    config = DockerConfig(binary=binary, image=inspected.stdout.strip(), context=context, threads=threads, memory_mb=memory_mb)
    provider = ColmapDocker(config)
    diagnostic = run / "native-diagnostic"
    diagnostic.mkdir()
    help_text = verified_command(provider, ["-h"], diagnostic, "help.log")
    if "COLMAP 3.9.1" not in help_text or "Usage:" not in help_text:
        raise RuntimeError("Image is not the verified COLMAP 3.9.1 CLI")
    for command, flags in provider.commands.items():
        output = verified_command(provider, [command, "-h"], diagnostic, command + "-help.log")
        if not all(flag in output for flag in flags):
            raise RuntimeError("COLMAP command has an incompatible option interface: " + command)
    # Explicit synthetic image: this verifies CPU extraction/matching, not SfM success.
    import cv2
    import numpy as np
    images = diagnostic / "images"
    images.mkdir()
    pixels = np.random.default_rng(42).integers(0, 256, (128, 128, 3), dtype=np.uint8)
    if not cv2.imwrite(str(images / "synthetic-fixture.png"), pixels):
        raise RuntimeError("Could not create synthetic CPU verification image")
    verified_command(provider, ["feature_extractor", "--database_path", "database.db", "--image_path", "images",
        "--ImageReader.camera_model", "SIMPLE_RADIAL", "--SiftExtraction.use_gpu", "0", "--SiftExtraction.num_threads", str(threads)],
        diagnostic, "cpu-features.log")
    verified_command(provider, ["exhaustive_matcher", "--database_path", "database.db", "--SiftMatching.use_gpu", "0",
        "--SiftMatching.num_threads", str(threads)], diagnostic, "cpu-matching.log")
    import sqlite3
    with closing(sqlite3.connect((diagnostic / "database.db").as_uri() + "?mode=ro", uri=True)) as db:
        if db.execute("SELECT COUNT(*) FROM images").fetchone()[0] != 1 or db.execute("SELECT SUM(rows) FROM keypoints").fetchone()[0] <= 0:
            raise RuntimeError("COLMAP CPU extraction did not write real features")
    body = dict(docker_bin=binary, image=config.image, context=context, threads=threads, memory_mb=memory_mb,
                colmap_version="3.9.1", package="3.9.1-2build2", evidence=str(run))
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=destination.parent, prefix="colmap-config-", suffix=".tmp", delete=False) as out:
            temp_path = Path(out.name)
            json.dump(body, out, indent=2)
        if destination.exists() and not force:
            raise ValueError("COLMAP config appeared during setup; refusing to overwrite")
        os.replace(temp_path, destination)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
    print(f"Verified COLMAP CPU image: {config.image}; config: {destination}", flush=True)
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker-bin", required=True)
    parser.add_argument("--context", default="desktop-linux")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--memory-mb", type=int, default=4096)
    parser.add_argument("--force-update", action="store_true")
    args = parser.parse_args()
    setup(ROOT, args.docker_bin, args.context, args.threads, args.memory_mb, args.force_update)


if __name__ == "__main__":
    main()
