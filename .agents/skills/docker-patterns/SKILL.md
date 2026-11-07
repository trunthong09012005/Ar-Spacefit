---
name: docker-patterns
description: Design or review AR-SpaceFit Dockerfiles, build contexts, container isolation, resource limits, networking and owned-container lifecycle. Use for Docker configuration changes; defer environment diagnosis to spacefit-devops and native stage contracts to spacefit-gen3d.
---

# Docker patterns for AR-SpaceFit

Adapted from the licensed reference-project skill; preserve `LICENSE`. Read `AGENTS.md`, `.agents/rules/data-and-runtime.md`, `docs/NATIVE_RUNTIME.md` and the affected Docker provider/tests before changing runtime configuration.

## Images and contexts

Dockerfiles live at `infra/docker/{colmap,openmvs}/`. Use the existing setup entrypoints and minimal staged build contexts. Pin base digests and runtime image IDs; validate upstream archive checksums. Keep dependency provenance and distinguish pinning from a fully reproducible build.

Do not build from the repository root or copy `.env`, job data, the Python virtualenv or host credentials into an image. Multi-stage builds and cache layering are useful only where the actual image benefits; do not add web-stack services or orchestration because a template includes them.

## Runtime boundaries

Native workers mount only an owned attempt or diagnostic workspace. Preserve read-only filesystem, temporary writable paths, dropped capabilities, no-new-privileges, bounded CPU/RAM/pids and network-none where supported by the current provider. Do not mount the Docker socket, whole repository, job DB or unrelated data. A user/UID change needs Windows/Linux file interoperability validation, not just a Dockerfile edit.

Keep timeout/cancel/shutdown cleanup tied to the exact owned container identity. Check other services and current jobs before any authorized daemon/WSL restart. Do not use broad prune, volume removal or unrelated-container cleanup as a debugging step.

If Compose is explicitly requested, inspect existing service ownership, bind only required ports, define useful health checks and distinguish persistent data from disposable fixtures. Compose health is not evidence of successful reconstruction. Linux container tests do not validate native Windows or macOS behavior.

## Verify

Use provider/installer contracts plus real startup and lifecycle checks when conditions allow; apply `$spacefit-gen3d` for native handoff changes and `$spacefit-verification` for acceptance. Record daemon/permission/image failures instead of weakening tests or claiming an image path is ready.
