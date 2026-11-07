"""Opt-in real CPU COLMAP/OpenMVS on synthetic images/scenes, not a real scan."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
import time

import cv2
import numpy as np
import pytest

from spacefit.config import Settings
from spacefit.docker_runtime import ColmapDocker, DockerConfig
from spacefit.native_runtime import probe_configured_tool
from spacefit.pipeline import Pipeline, Cancelled
from spacefit.store import JobStore
from test_openmvs_docker_integration import colmap_text_fixture

CONFIG_PATH = os.getenv("SPACEFIT_COLMAP_DOCKER_TEST_CONFIG")
pytestmark = pytest.mark.skipif(not CONFIG_PATH, reason="Real COLMAP Docker needs explicit test config")


def load_config(path):
    raw = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    return DockerConfig(binary=raw["docker_bin"], image=raw["image"], context=raw["context"],
                        threads=raw["threads"], memory_mb=raw["memory_mb"])


@pytest.fixture
def fixture(docker_tmp_path):
    tmp_path = docker_tmp_path
    config = load_config(CONFIG_PATH)
    settings = Settings(data_dir=tmp_path / "qa-data", colmap_runtime="docker", colmap_docker=config, stage_timeout=60)
    store = JobStore(settings.data_dir)
    job = store.create({"name":"Synthetic COLMAP fixture"}, [], status="processing")
    work = settings.data_dir / job["id"] / "attempt-fixture"
    work.mkdir(parents=True)
    return Pipeline(store, settings), job, work, config


@pytest.mark.parametrize("args", [["-h"], *[[command, "-h"] for command in ColmapDocker.commands]])
def test_real_cpu_cli_and_all_pipeline_commands_load(fixture, args):
    pipeline, job, work, config = fixture
    pipeline.run_colmap(job["id"], "colmap_help_fixture", 1, args, work)
    log = (work / "pipeline.log").read_text(encoding="utf-8")
    assert "COLMAP 3.9.1" in log and "--" in log
    if len(args) == 1:
        result = probe_configured_tool(pipeline.settings, "colmap", config.binary, work / "probe", 30)
        assert result["status"] == "ready" and result["exit"] == 0


def test_real_cpu_feature_extraction_and_matching_write_database(fixture):
    pipeline, job, work, config = fixture
    images = work / "images"
    images.mkdir()
    pixels = np.random.default_rng(173).integers(0, 256, (256, 256, 3), dtype=np.uint8)
    for index in range(2):
        assert cv2.imwrite(str(images / f"synthetic-{index}.png"), np.roll(pixels, index*5, axis=1))
    pipeline.run_colmap(job["id"], "features", 12, ["feature_extractor", "--database_path", str(work / "database.db"),
        "--image_path", str(images), "--ImageReader.camera_model", "SIMPLE_RADIAL", "--SiftExtraction.use_gpu", "0",
        "--SiftExtraction.num_threads", str(config.threads)], work)
    pipeline.run_colmap(job["id"], "matching", 25, ["exhaustive_matcher", "--database_path", str(work / "database.db"),
        "--SiftMatching.use_gpu", "0", "--SiftMatching.num_threads", str(config.threads)], work)
    with closing(sqlite3.connect((work / "database.db").as_uri()+"?mode=ro", uri=True)) as db:
        assert db.execute("SELECT COUNT(*) FROM images").fetchone()[0] == 2
        assert db.execute("SELECT MIN(rows) FROM keypoints").fetchone()[0] > 0
        assert db.execute("SELECT SUM(rows) FROM matches").fetchone()[0] > 0
    assert not (work / "model.glb").exists()


def test_real_colmap_undistortion_hands_binary_scene_to_openmvs(fixture):
    pipeline, job, work, _ = fixture
    mvs_path = os.getenv("SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG")
    assert mvs_path, "COLMAP/OpenMVS interoperability requires both explicit QA configs"
    pipeline.settings.openmvs_runtime = "docker"
    pipeline.settings.docker = load_config(mvs_path)
    source = colmap_text_fixture(work)  # Three known cameras/eight points, synthetic PNGs.
    output = work / "undistorted"
    pipeline.run_colmap(job["id"], "undistortion", 48, ["image_undistorter", "--image_path", str(source / "images"),
        "--input_path", str(source / "sparse"), "--output_path", str(output), "--output_type", "COLMAP", "--max_image_size", "2000"], work)
    assert (output / "sparse/images.bin").stat().st_size > 100
    assert len(list((output / "images").glob("*.png"))) == 3
    pipeline.run_mvs(job["id"], "mvs_import", 53, "InterfaceCOLMAP", ["-i", str(output), "-o", "scene.mvs",
        "--image-folder", str(output / "images")], work)
    assert (work / "scene.mvs").stat().st_size > 100
    log = (work / "pipeline.log").read_text(encoding="utf-8", errors="replace").lower()
    assert "3 images" in log and "8 points" in log


def test_real_mapper_invalid_input_fails_without_erasing_attempt(fixture):
    pipeline, job, work, config = fixture
    marker = work / "preserve.txt"
    marker.write_text("synthetic input marker", encoding="utf-8")
    with pytest.raises(RuntimeError, match="failed \\(exit"):
        pipeline.run_colmap(job["id"], "camera_poses", 38, ["mapper", "--database_path", str(work / "missing.db"),
            "--image_path", str(work / "missing-images"), "--output_path", str(work / "missing-sparse"),
            "--Mapper.num_threads", str(config.threads)], work)
    assert marker.read_text(encoding="utf-8") == "synthetic input marker"
    log = (work / "pipeline.log").read_text(encoding="utf-8", errors="replace")
    assert "Check failed: ExistsDir(*image_path)" in log
    assert "Invalid options provided." in log


def test_real_mapper_recovers_known_synthetic_camera_correspondences(fixture):
    """Real mapper on seeded geometric correspondences, NOT image matching accuracy.

    Extraction creates the real version's schema. Only this QA database is then
    populated with known projections of 200 non-planar points in five cameras.
    """
    pipeline, job, work, config = fixture
    images = work / "images"
    images.mkdir()
    rng = np.random.default_rng(84)
    for index in range(5):
        assert cv2.imwrite(str(images / f"synthetic-{index}.png"), rng.integers(0, 256, (480, 640, 3), dtype=np.uint8))
    pipeline.run_colmap(job["id"], "fixture_schema", 1, ["feature_extractor", "--database_path", "database.db", "--image_path", "images",
        "--ImageReader.camera_model", "SIMPLE_RADIAL", "--SiftExtraction.use_gpu", "0", "--SiftExtraction.num_threads", str(config.threads)], work)
    points = rng.uniform([-.8, -.7, 3.5], [.8, .7, 6.5], (200, 3))
    k = np.array([[320.,0,320],[0,320,240],[0,0,1]], dtype=np.float64)
    # Close the Windows SQLite handle before Linux opens the same QA database.
    # sqlite3's transaction context alone commits but does NOT close the handle.
    with closing(sqlite3.connect(work / "database.db")) as db, db:
        rows = db.execute("SELECT image_id FROM images ORDER BY name").fetchall()
        for table in ("keypoints", "descriptors", "matches", "two_view_geometries", "cameras"):
            db.execute("DELETE FROM " + table)  # Fixed table list, QA-only database.
        db.execute("INSERT INTO cameras(camera_id,model,width,height,params,prior_focal_length) VALUES(1,1,640,480,?,1)",
                   (np.array([320.,320.,320.,240.], dtype=np.float64).tobytes(),))
        db.execute("UPDATE images SET camera_id=1")
        centers = np.linspace(-2., 2., 5)
        for (image_id,), center in zip(rows, centers):
            coordinates = np.column_stack((320*(points[:,0]-center)/points[:,2]+320, 320*points[:,1]/points[:,2]+240)).astype(np.float32)
            db.execute("INSERT INTO keypoints(image_id,rows,cols,data) VALUES(?,?,2,?)", (image_id,200,coordinates.tobytes()))
        for i in range(5):
            for j in range(i+1,5):
                first, second = rows[i][0], rows[j][0]
                tx = centers[i]-centers[j]
                if first > second:
                    first, second, tx = second, first, -tx
                pair_id = first*2147483647 + second
                matches = np.column_stack((np.arange(200),np.arange(200))).astype(np.uint32).tobytes()
                essential = np.array([[0.,0,0],[0,0,-tx],[0,tx,0]])
                fundamental = np.linalg.inv(k).T @ essential @ np.linalg.inv(k)
                db.execute("INSERT INTO two_view_geometries(pair_id,rows,cols,data,config,F,E,H,qvec,tvec) VALUES(?,200,2,?,2,?,?,?,?,?)",
                    (pair_id,matches,fundamental.tobytes(),essential.tobytes(),np.eye(3).tobytes(),
                     np.array([1.,0,0,0]).tobytes(),np.array([tx,0.,0]).tobytes()))
    sparse = work / "sparse"
    sparse.mkdir()
    pipeline.run_colmap(job["id"], "camera_poses", 38, ["mapper", "--database_path", "database.db", "--image_path", "images",
        "--output_path", "sparse", "--Mapper.num_threads", str(config.threads)], work)
    reconstructed = list(sparse.glob("*/images.bin"))
    assert reconstructed
    assert max(struct.unpack("<Q", path.read_bytes()[:8])[0] for path in reconstructed) == 5
    assert max(struct.unpack("<Q", (path.parent / "points3D.bin").read_bytes()[:8])[0] for path in reconstructed) >= 180


@pytest.mark.parametrize("ending", ["timeout", "cancel"])
def test_real_colmap_container_cleanup_on_timeout_or_cancel(fixture, ending):
    pipeline, job, work, config = fixture
    pipeline.settings.stage_timeout = 4 if ending == "timeout" else 30
    prepared = ColmapDocker(config).prepare("colmap", ["-h"], work)
    # Explicit infrastructure sleep fixture; production ColmapDocker rejects this argv.
    argv = prepared.argv[:prepared.argv.index(config.image)+1] + ["sleep", "30"]
    def inspect():
        return subprocess.run([config.binary, "--context", config.context, "container", "inspect", "--format",
            "{{.State.Running}}", prepared.container_name], capture_output=True, timeout=10)
    def run():
        with prepared:
            pipeline.run(job["id"], "colmap_cleanup_fixture", 1, argv, work)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(run)
            deadline = time.monotonic()+3
            running = False
            while time.monotonic() < deadline:
                response = inspect()
                if response.returncode == 0 and response.stdout.strip() == b"true":
                    running = True
                    break
                time.sleep(.05)
            assert running
            if ending == "cancel":
                pipeline.store.update(job["id"], status="cancelled")
            with pytest.raises(Cancelled if ending == "cancel" else TimeoutError):
                future.result(timeout=20)
        assert prepared.cleanup_error is None and inspect().returncode != 0
        assert work.is_dir() and (work / "pipeline.log").is_file()
    finally:
        prepared.cleanup()
