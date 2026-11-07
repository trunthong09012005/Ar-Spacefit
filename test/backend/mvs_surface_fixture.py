"""Synthetic textured height-field and known cameras, NOT a user object scan.

Starts at a known point cloud: tests mesh/refine/texture exchange, not feature
matching or densification quality. Images are rendered analytically on the CPU.
"""
import cv2
import numpy as np


def surface_scene(work):
    dense = work / "dense"
    sparse, images = dense / "sparse", dense / "images"
    sparse.mkdir(parents=True)
    images.mkdir()
    width, height, focal = 640, 480, 500
    (sparse / "cameras.txt").write_text("1 PINHOLE 640 480 500 500 320 240\n", encoding="utf-8")

    def depth(x, y):
        return 4 + .15 * np.sin(2*x) * np.cos(2*y)

    points = [(x, y, float(depth(x, y)))
              for y in np.linspace(-1.4, 1.4, 35) for x in np.linspace(-1.8, 1.8, 45)]
    centers = (-.4, 0., .4)
    py, px = np.indices((height, width), dtype=float)
    dx, dy = (px - width/2) / focal, (py - height/2) / focal
    image_lines = []
    for image_id, center in enumerate(centers, 1):
        z = np.full((height, width), 4.)
        for _ in range(12):
            z = depth(center + dx*z, dy*z)
        x, y = center + dx*z, dy*z
        pattern = np.stack([125+75*np.sin(25*x)*np.cos(23*y),
                            125+75*np.sin(17*x+11*y), 125+75*np.cos(19*y-9*x)], axis=-1).astype(np.uint8)
        name = f"synthetic-surface-{image_id}.png"
        assert cv2.imwrite(str(images / name), pattern)
        image_lines.append(f"{image_id} 1 0 0 0 {-center} 0 0 1 {name}")
        image_lines.append(" ".join(f"{focal*(x-center)/z+320} {focal*y/z+240} {i}"
                                    for i, (x, y, z) in enumerate(points, 1)))
    (sparse / "images.txt").write_text("\n".join(image_lines)+"\n", encoding="utf-8")
    (sparse / "points3D.txt").write_text("\n".join(
        f"{i} {x} {y} {z} 120 150 180 0 " + " ".join(f"{j} {i-1}" for j in (1, 2, 3))
        for i, (x, y, z) in enumerate(points, 1))+"\n", encoding="utf-8")
    return dense
