---
name: spacefit-gen3d
description: Diagnose or change AR-SpaceFit photogrammetry, COLMAP/OpenMVS Docker stages, Blender GLB export, texture/render quality, crop, orientation or physical scale. Use for native reconstruction and actual scan quality; exclude generic image generation and unrelated Docker services.
---

# AR-SpaceFit Gen3D and native runtime

Read `AGENTS.md`, `docs/WORK_STATUS.md`, `docs/NATIVE_RUNTIME.md`, and `docs/REAL_SCAN_ACCEPTANCE.md` for the current task. The implemented flow is images → COLMAP camera reconstruction → OpenMVS dense/mesh/refine/texture → Blender preview → reviewed crop/orientation/scale → final GLB.

## Diagnose the layer supported by evidence

- Startup/container failure: inspect readiness, exact executable/image, exit status and owned diagnostic evidence. Keep startup success separate from reconstruction quality.
- Poor reconstruction: inspect authorized capture samples, accepted/registered views and actual sparse/dense/mesh artifacts. Registered background cameras alone do not prove the object was recovered. More photos are not automatically a remedy.
- Dark or broken preview: compare the same GLB in an independent renderer, inspect embedded textures/UVs/materials and compare with a neutral material render on a QA copy. Do not conclude missing lighting, missing geometry or bad photography from one screenshot.
- Wrong dimensions: distinguish arbitrary-unit preview, orientation/crop and final scale. Use one measured axis for scale and another independently measured axis for error; do not force all dimensions to the known answer.

Use only task-authorized artifacts and avoid unrelated private data. Preserve originals and write diagnostic renders/derived files to a new QA directory. Never substitute a stock or AI-generated object for a failed measured reconstruction.

## Preserve the stage contract

Inspect `backend/spacefit/pipeline.py`, `docker_runtime.py`, `native_runtime.py`, `backend/scripts/export_glb.py` and the scoped provider tests for the stage being changed. Follow the existing pinned CLI contract rather than guessing options for a newer upstream version.

Keep binary MVS scene handoff and required nonempty stage outputs. Do not silently use a sparse scene where dense data is required. Retain cancellation/timeout cleanup for the exact owned container, immutable image IDs and attempt-only mounts; Dockerfiles are in `infra/docker/`. Do not mount the real job DB, secrets, Docker socket or entire repository into a native runtime.

Exit 137 is a killed process; confirm an owned OOM event before labeling it out of memory. CPU/thread/memory changes need a measured explanation of speed/quality tradeoffs; do not quietly skip refine, lower resolution or replace output to turn failure into success. Check other services before any authorized Docker/WSL restart.

## Acceptance

Use native integration fixtures for handoff/UV/export/cleanup regressions and real Blender pixel evidence for a rendering claim. Apply `$spacefit-verification` with native requirements for pipeline acceptance. For actual scans, record object identity, artifact version, capture conditions, visible defects and independently measured dimensions. Report unknown measurements as unknown. A pretrained image-to-3D experiment is a separate product mode with inferred unseen geometry, not validated metrology or proof that the scan pipeline works.
