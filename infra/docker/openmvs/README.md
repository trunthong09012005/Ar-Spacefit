# OpenMVS Linux CPU image

This image packages the **official OpenMVS v2.4.0 Ubuntu x64 CPU release**;
it does not replace reconstruction with a mock. The source revision is
`58117204c86bbb11a0b25b26a8987676cf11274d` and the archive SHA-256 is
`7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00`.
The Ubuntu 24.04 linux/amd64 base is pinned to manifest digest
`sha256:f610ab94648195aa356059f5b41d6085c9d4d903c072430cdd1af7bdb646106b`.
Ubuntu runtime packages are installed from signed Ubuntu repositories; their
versions are not a fully frozen apt snapshot. The provisioned runtime uses the
final local **immutable image ID**, not a mutable tag.

Run `tools/Setup-OpenMvsDocker.ps1` from PowerShell. It creates a unique isolated
build/evidence directory under `.tools/qa`, verifies the official archive before
building, starts all five actual CLI help commands with restricted ephemeral
containers, and writes the machine-local `backend/native-docker.json` only when
all five startup checks pass. It never writes `.env`, changes Windows security,
starts a reconstruction job, logs in to Docker or deletes user resources.

There is **no ENTRYPOINT**; `/opt/openmvs/bin` is on PATH. CLI convention:
`docker run [restricted options] IMAGE_ID InterfaceCOLMAP ...`.
Default command is `InterfaceCOLMAP -h`, not a long-lived server. Writable bind
mounts must be limited to an individual job attempt or owned QA folder. Help
commands return exit 1 on this version; accepted startup also requires the real
OpenMVS banner and options text. A startup PASS is not model-quality acceptance.

Primary provenance:

- [Official v2.4.0 release](https://github.com/cdcseacave/openMVS/releases/tag/v2.4.0).
- [Official CPU build workflow](https://github.com/cdcseacave/openMVS/blob/v2.4.0/.github/workflows/continuous_integration.yml).
- [Official release assets API](https://api.github.com/repos/cdcseacave/openMVS/releases/tags/v2.4.0).
- [Docker image pinning guidance](https://docs.docker.com/build/building/best-practices/#pin-base-image-versions).
- [Docker run isolation/resource options](https://docs.docker.com/engine/containers/run/).

OpenMVS is licensed under AGPL-3.0; consult the upstream license before
redistributing this image or providing a hosted service. This repository does
not publish the image automatically.
