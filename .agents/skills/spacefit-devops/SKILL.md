---
name: spacefit-devops
description: Diagnose or configure AR-SpaceFit developer environments, Docker images, PowerShell tooling, builds and requested CI/CD workflows. Use for infra/docker, tools and build/CI environment tasks; exclude automatic deployment, unrelated services and photogrammetry quality tuning.
---

# AR-SpaceFit development infrastructure

Read `AGENTS.md`, `.agents/rules/data-and-runtime.md`, `docs/RUNBOOK.md` and configuration source relevant to the task. Follow `docs/NATIVE_RUNTIME.md` for native runtime and `$spacefit-gen3d` if a native stage contract changes. Distinguish environment failures from product defects.

## Verify the environment

Verify relevant executable/version, working directory, JDK/SDK/Gradle cache, Python venv, Docker context and data drive. Check live services and owned jobs before stopping processes/restarting Docker or WSL; historical PIDs/idle counts are insufficient. Inspect task-scoped non-secret configuration, never dump `.env` or a process environment.

Gradle runs at repo root with `:app` mapped to `apps/android/`; Python is in `backend/`; Dockerfiles are in `infra/docker/{colmap,openmvs}/`; operational entrypoints are in `tools/`. Preserve local virtualenv/data/config paths when reorganizing code.

## Containers and builds

Keep immutable native image IDs, verified upstream archives and minimal staged build contexts. Do not build at repository root, send data/secrets into contexts or expose the Docker socket/broad mounts to native jobs. Preserve resource limits and exact owned-container cleanup. Make persistent machine changes only within the requested configuration task; do not disable Windows protection for native binaries.

Keep diagnostics and validation-only dependencies in isolated QA paths. Do not upgrade the entire dependency tree to resolve one environment failure.

## CI/CD when requested

Inspect workflows before adding one; do not assume `.github/workflows/` exists. Preserve Android/Windows/native requirements when selecting runners. Distinguish unit/HTTP checks from resource-heavy native/device acceptance; record missing conditions, retain failure artifacts and use fake test tokens/temp data. Limit workflow permissions and secret exposure to actual needs.

For GitHub Actions failures use `$gh-fix-ci`. Installing skills does not create a workflow or authorize pushes, deployment, cloud services or paid runners. Keep release signing outside the repo and reuse the existing key for updates; a debug build does not establish release signing.

Verify the changed path and use `$spacefit-verification` when product/build code changes. Record native/UI prerequisites that remain unavailable.
