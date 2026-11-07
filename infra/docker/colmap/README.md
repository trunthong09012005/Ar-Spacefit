# COLMAP CPU Docker

This image uses Ubuntu's signed package repository: [`colmap=3.9.1-2build2`](https://packages.ubuntu.com/noble/colmap), on a digest-pinned Ubuntu 24.04 base. It is a distribution CPU build, not the upstream CUDA Docker image. Windows COLMAP 4.2 remains installed but is not used in Docker mode.

Only `Dockerfile` enters the temporary build context. Runtime uses an immutable image ID and never pulls by tag. Ubuntu dependencies are not snapshot-pinned: the build is not claimed bit-for-bit reproducible. Machine-local config and artifacts are ignored by Git.

After owner approval, run `backend/scripts/setup_colmap_docker.py --docker-bin <path>` with the backend virtual environment. The installer verifies daemon/workspace, builds, validates version and all four command interfaces, runs CPU extraction/matching on an explicit synthetic image, closes its QA SQLite handle, then atomically publishes `backend/native-colmap-docker.json`. Existing config requires explicit `--force-update`; failures preserve it.

The provider allows only fixed help or pipeline argv for feature extraction, exhaustive matching, mapper and image undistorter. GPU is disabled, threads match container limits. Only an attempt/empty diagnostic is mounted at `/workspace`; no network, Docker socket, secrets or job database. The feature SQLite database is part of that isolated attempt. Core dumps are disabled; each UUID-owned container is removed after success/failure/cancel/timeout.

Tests distinguish real extraction/matching from mapper seeded with known geometric correspondences. The latter checks the actual mapper and binary output, not reconstruction from captured photographs. Undistortion hands a synthetic scene to real OpenMVS. Actual chair/room acceptance remains manual.
