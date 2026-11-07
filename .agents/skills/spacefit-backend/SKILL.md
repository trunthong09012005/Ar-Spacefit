---
name: spacefit-backend
description: Implement or diagnose AR-SpaceFit FastAPI, SQLite job storage, upload/artifact APIs, worker lifecycle, or Android-to-backend contracts. Use for backend/spacefit and related API tests; exclude Android-only and unrelated services.
---

# AR-SpaceFit backend

Read `AGENTS.md`, `docs/WORK_STATUS.md`, `backend/README.md` and relevant API/persistence tests. The service package is `backend/spacefit/`; administrative entrypoints are in `backend/scripts/`. Keep `.venv`, configuration and real job data at their existing paths.

## Follow the request through its owners

Inspect `api.py`, `models.py`, `store.py` and `pipeline.py` as needed before changing their contract. Compare Android request/response handling in `apps/android/src/main/java/com/example/reconstruction/` when a public field, error or status changes. Importing the API must not create a DB or start a worker.

Treat job state, attempt ownership and idempotency as one workflow. A retry must preserve original inputs and earlier attempts; cancellation followed by retry must not let the old attempt publish output into the new state. Calibration needs only the exporter, not an entire reconstruction run.

Keep readiness based on actual startup results and their freshness. An existing binary or configured image is insufficient. Preserve fail-closed upload and worker preflight when checking, expired or failed.

For SQLite changes, inspect schema/migration behavior and test old/new data in a disposable DB. Explicitly close connections before passing a fixture between Windows and Linux; a transaction context alone does not close a connection.

## API and worker validation

Preserve auth, body/file limits, UUID/path validation, safe artifact resolution, token-safe errors and argument-list subprocess execution. For changes to these boundaries, test malformed input and unauthorized requests alongside the success path. Apply the installed security guidance when the task explicitly requests a security review or secure-by-default implementation; its Python/FastAPI guidance is not Kotlin coverage.

Tests use a fresh data directory, fake token and separate loopback port. Use unit/component tests for validation/state transitions; use actual HTTP/Uvicorn black-box tests for public behavior. Label native mocks and seeded states; neither is a captured-object reconstruction success. Apply `$spacefit-gen3d` if changing native stages, and `$spacefit-verification` before code handoff.
