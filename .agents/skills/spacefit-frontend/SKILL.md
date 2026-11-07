---
name: spacefit-frontend
description: Design, review or improve AR-SpaceFit Android interfaces with Jetpack Compose, Material theming, accessibility, responsive layout, forms and loading/error states. Use for UI/UX work in apps/android; exclude backend-only tasks and unrequested React or web apps.
---

# AR-SpaceFit frontend and UX

The product frontend is the Android app at `apps/android/`. Read `AGENTS.md`, then inspect the affected screen, `ui/theme/` and its state owner. Apply `$spacefit-android` when implementing Compose/state/lifecycle changes and `$spacefit-verification` for code handoff.

## Fit the product

Trace the user's task from entry to success or recovery. Preserve Vietnamese terminology, shared components and theme; change design patterns when the requested outcome needs it. Distinguish capture, laptop processing, unscaled preview and confirmed furniture instead of labeling intermediate states as complete.

Design the empty, loading, error, disabled, offline and success states relevant to the task. Explain recovery in user language; show internal DLL names or exit codes only where useful for diagnosis. Keep access to technical detail without making it the main product instruction.

## Compose and accessibility

Check small displays, larger system fonts, supported orientation, scrolling, keyboard/IME insets and focus. Use semantic labels, content descriptions, meaningful contrast and touch targets; do not communicate errors solely by color. Prefer Material/theme values to screen-specific hard-coded styling. Check that actions remain reachable under keyboards and dialogs.

Keep navigation/asynchronous state aligned with existing ViewModel and persistence behavior. A visual refresh must not discard capture input, trigger unintended upload/retry, replace a verified GLB or alter physical scale. Distinguish a viewport presentation change from modifying the exported object's geometry/material/units.

## Evidence

Use relevant Compose interaction tests and visual inspection on stable QA emulator for layout/clipping. Native GLB visibility needs pixel evidence. Follow emulator-only data-writing rules and retain fixture identity. Report device/accessibility combinations not actually checked; a mockup does not establish working Android behavior.
