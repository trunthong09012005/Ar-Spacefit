---
name: spacefit-verification
description: Select, run and interpret AR-SpaceFit regression, unit, integration, HTTP black-box, Blender/native and Android QA checks before handing off code changes or when testing is requested. Do not run product suites solely for skill/documentation installation.
---

# AR-SpaceFit verification

Read `AGENTS.md`, `docs/TESTING.md` and current `docs/WORK_STATUS.md`. Match the checks to the changed behavior; do not treat a previous run or a successful compile as a new test result.

## Choose evidence that can establish the claim

| Change | Relevant evidence |
|---|---|
| Validation, geometry, codecs | Backend unit or Android JVM assertions on outputs/boundaries |
| Job state, DB schema, persistence | Disposable DB/state transition tests; migration with old/new data |
| Public API / network recovery | Actual loopback HTTP plus client integration; invalid/auth/timeout paths |
| Native handoff/export | Opt-in real Docker/Blender fixture chain; distinguish seeded inputs/mocks |
| GLB rendering | Visible pixel evidence and geometry/texture checks; callback alone is insufficient |
| Android flow | JVM integration plus stable QA emulator instrumentation as relevant |
| Actual scan, dimensions, AR | Real object/device and recorded measurements; fixture results do not substitute |

Keep fixtures explicitly named. Do not loosen assertions, disable a failing check or count deselected/skipped/unexecuted tests as pass. An environment failure remains a failure/blocker in the required gate.

## Run and isolate

Use `tools/Test-All.ps1` before handing off product code changes. Android output is at `apps/android/build/`; backend tests are at `test/backend/`. Consult the runner's actual options rather than inventing flags. Select targeted tests during work, then run the repository-required gate. For native pipeline acceptance include `-RequireNative`, the configured Docker requirements and Blender; startup checks must execute rather than only find paths.

Allocate a unique QA run, data directory and loopback port. Pass a nonexistent child to pytest `--basetemp`; pytest can delete that directory. Use fake tokens, and never the real `backend/data/jobs.sqlite3`. Standalone doctor supports `--no-config` for isolated checks; do not read/print real `.env` contents as part of diagnosis. Read the actual runner/doctor configuration behavior before invoking native checks.

For instrumentation, verify the dedicated SpaceFitQA identity, completed boot, no ANR/error dialog and stable system services. Follow the existing emulator-only guard; do not substitute a real phone or wipe data to bypass environment failures.

## Count and report

Read fresh JUnit for each executed group, check command exit codes and retain failure evidence. Keep per-group pass/fail/error/skip counts and gate exit status. For instrumentation also require its successful test summary; an adb exit code alone is insufficient. Copy/hash the built artifact only after build success.

Update `docs/TEST_RESULTS.md` with commands, counts and evidence paths, and `docs/WORK_STATUS.md` with remaining work. Separate independent runs instead of combining them into an imaginary passing gate. Do not claim measured coverage, reconstruction success or a quality/performance improvement without corresponding evidence.
