"""Optional real Blender tests; set BLENDER_BIN explicitly to enable.

Synthetic textured boxes exercise export/calibration only, not reconstruction
accuracy. No fixture is inserted into the running service's job database.
"""
import json
import os
import shutil
import struct

import cv2
import numpy as np
import pytest

from spacefit.config import Settings
from spacefit.pipeline import Pipeline
from spacefit.store import JobStore


BLENDER = shutil.which(os.environ.get("BLENDER_BIN", ""))
pytestmark = pytest.mark.skipif(not BLENDER, reason="Set BLENDER_BIN to run real Blender export tests")


def write_fixture(work, extra_box=False):
    """OBJ coordinates are glTF Y-up, deliberately offset from the origin."""
    lines = ["mtllib fixture.mtl", "usemtl checker", "vt 0 0", "vt 1 0", "vt 1 1", "vt 0 1"]
    faces = [(1, 4, 3, 2), (5, 6, 7, 8), (1, 2, 6, 5), (4, 8, 7, 3), (1, 5, 8, 4), (2, 3, 7, 6)]
    for index, offset in enumerate((0, 5) if extra_box else (0,)):
        vertices = [(10, 20, 30), (12, 20, 30), (12, 23, 30), (10, 23, 30),
                    (10, 20, 34), (12, 20, 34), (12, 23, 34), (10, 23, 34)]
        lines.extend(f"v {x + offset} {y} {z}" for x, y, z in vertices)
        lines.extend("f " + " ".join(f"{v + index * 8}/{uv}" for uv, v in enumerate(face, 1)) for face in faces)
    (work / "fixture_texture.obj").write_text("\n".join(lines), encoding="utf-8")
    (work / "fixture.mtl").write_text("newmtl checker\nKd 1 1 1\nmap_Kd checker.png\n", encoding="utf-8")
    checker = (np.indices((16, 16)).sum(axis=0) % 2 * 200 + 30).astype(np.uint8)
    assert cv2.imwrite(str(work / "checker.png"), np.stack([checker, 255 - checker, checker], axis=-1))


def read_glb(path):
    raw = path.read_bytes()
    assert struct.unpack_from("<III", raw) == (0x46546C67, 2, len(raw))
    json_length, chunk_type = struct.unpack_from("<II", raw, 12)
    assert chunk_type == 0x4E4F534A
    document = json.loads(raw[20:20 + json_length])
    binary_length, binary_type = struct.unpack_from("<II", raw, 20 + json_length)
    assert binary_type == 0x004E4942
    binary = raw[28 + json_length:]
    assert len(binary) == binary_length
    return document, binary


def world_bounds(document, binary):
    """Read actual GLB POSITION data, including node transforms, not metadata."""
    points = []

    def visit(index, parent):
        node = document["nodes"][index]
        if "matrix" in node:
            local = np.array(node["matrix"]).reshape((4, 4), order="F")
        else:
            x, y, z, w = node.get("rotation", [0, 0, 0, 1])
            rotation = np.array([
                [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)],
            ])
            local = np.eye(4)
            local[:3, :3] = rotation @ np.diag(node.get("scale", [1, 1, 1]))
            local[:3, 3] = node.get("translation", [0, 0, 0])
        transform = parent @ local
        if "mesh" in node:
            for primitive in document["meshes"][node["mesh"]]["primitives"]:
                accessor = document["accessors"][primitive["attributes"]["POSITION"]]
                assert accessor["componentType"] == 5126 and accessor["type"] == "VEC3"
                view = document["bufferViews"][accessor["bufferView"]]
                start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
                for i in range(accessor["count"]):
                    point = struct.unpack_from("<fff", binary, start + i * view.get("byteStride", 12))
                    points.append((transform @ np.array([*point, 1]))[:3])
        for child in node.get("children", []):
            visit(child, transform)

    for root in document["scenes"][document.get("scene", 0)]["nodes"]:
        visit(root, np.eye(4))
    return np.min(points, axis=0), np.max(points, axis=0)


def prepare(tmp_path, *, final=False, crop=False):
    store = JobStore(tmp_path)
    job = store.create({"name": "Synthetic export test"}, [], status="calibrating" if final else "processing")
    root = tmp_path / job["id"]
    work = root / "attempt-test"
    work.mkdir(parents=True)
    write_fixture(work, extra_box=crop)
    calibration = dict(reference_axis="width", reference_cm=80, rotation_x=0,
                       rotation_y=0 if crop else 90, rotation_z=0,
                       crop_min=[0, 0, 0], crop_max=[0.5 if crop else 1, 1, 1], reviewed=True)
    job = store.update(job["id"], work_dir=work.name, calibration=calibration if final else None)
    pipeline = Pipeline(store, Settings(data_dir=tmp_path, stage_timeout=120))
    return pipeline, job, root


@pytest.mark.parametrize("final,crop,expected", [
    (False, False, [2, 3, 4]),
    (True, False, [0.8, 0.6, 0.4]),
    (True, True, [0.8, 1.2, 1.6]),
])
def test_real_blender_exports_textured_glb_with_correct_scale(tmp_path, final, crop, expected):
    pipeline, job, root = prepare(tmp_path, final=final, crop=crop)
    pipeline.export(job, root, BLENDER, final=final)
    saved = pipeline.store.get(job["id"])
    assert saved["status"] == ("completed" if final else "needs_review")
    result = saved["result"]
    assert result["scale_confirmed"] is final
    assert result["units"] == ("metres" if final else "arbitrary")
    assert result["polygon_count"] == 12
    np.testing.assert_allclose([result[k] / 100 for k in ("width_cm", "height_cm", "depth_cm")], expected, atol=1e-5)
    document, binary = read_glb(root / ("model.glb" if final else "preview.glb"))
    low, high = world_bounds(document, binary)
    np.testing.assert_allclose(high-low, expected, atol=1e-5)
    np.testing.assert_allclose([(high[0]+low[0])/2, low[1], (high[2]+low[2])/2], [0, 0, 0], atol=1e-5)
    # UVs and the image must survive, with image bytes embedded for offline use.
    assert document["textures"]
    assert all("bufferView" in image and image["mimeType"].startswith("image/") for image in document["images"])
    assert all("TEXCOORD_0" in p["attributes"] for m in document["meshes"] for p in m["primitives"])


def test_real_blender_reports_empty_crop_as_failed_job(tmp_path, monkeypatch):
    pipeline, job, root = prepare(tmp_path, final=True)
    monkeypatch.setenv("BLENDER_BIN", BLENDER)
    calibration = dict(job["calibration"], crop_min=[0.4, 0.4, 0.4], crop_max=[0.6, 0.6, 0.6])
    job = pipeline.store.update(job["id"], status="calibration_queued", calibration=calibration)
    pipeline.process(job)
    saved = pipeline.store.get(job["id"])
    assert saved["status"] == "failed"
    assert not (root / "model.glb").exists()
    assert "Crop removed the entire mesh" in (root / job["work_dir"] / "pipeline.log").read_text(encoding="utf-8")
