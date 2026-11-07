---
name: spacefit-android
description: Implement or diagnose AR-SpaceFit Android changes involving Kotlin, Compose, CameraX capture, Room persistence, GLB viewing, or ARCore. Use for work in apps/android; exclude backend-only and documentation-only tasks.
---

# AR-SpaceFit Android

Read the repository's `AGENTS.md`, `docs/WORK_STATUS.md` and the relevant sections of `docs/TESTING.md`. Resolve paths from the repository root containing `settings.gradle.kts`; the Android module is `:app` at `apps/android/`.

## Locate the owning flow

- Capture, pairing, upload/poll/download and calibration: `src/main/java/com/example/reconstruction/` and `ui/ReconstructionScreen.kt`.
- GLB preview and native render configuration: `ui/GlbViewer.kt`, `ui/RenderView.kt`; placement/AR also involve `ui/RoomScene.kt`, `ui/PhysicalArScreen.kt` and `ar/`.
- Inventory/project persistence and migration: `data/`; geometry and placement constraints: `spacefit/`.
- Preserve the current application ID, namespace and existing Room schema history unless the requested change explicitly requires migration.

## Decisions that affect correctness

Keep Compose rendering free of blocking file/network/native work. Review coroutine ownership, cancellation and main-thread affinity for Filament/SceneView; check lifecycle recovery when changing capture or AR. Inspect the pinned library version before relying on a rendering default.

Preserve the upload idempotency key and locked input across retries, process recreation and lost HTTP replies. Never replace a verified model with an unscaled preview or partial download. Revalidate persisted item/project state before placement when asynchronous work finishes.

For Room changes, add a migration and verify real old-schema data survives; keep schema exports. A UI label change alone does not prove a state transition, and a GLB loaded callback does not prove visible geometry or correct pixels.

## Verification and handoff

Use JVM tests under `test/android/unit/` for validation/geometry and Room/HTTP integration where relevant. Run state-changing instrumentation under `test/android/instrumentation/` only on a stable dedicated SpaceFitQA emulator, following the repository preflight. Use neutral render fixtures with pixel evidence for rendering changes; report real scan/AR separately.

Run Gradle from the repository root, e.g. `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; APKs and JUnit are under `apps/android/build/`. Apply `$spacefit-verification` when handing off code changes. Do not turn an Android build into authorization to install, clear or uninstall an app on a real phone.
