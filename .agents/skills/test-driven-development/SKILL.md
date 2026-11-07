---
name: test-driven-development
description: Apply a red-green-refactor cycle to AR-SpaceFit behavior changes and bug fixes with meaningful regression tests. Use before product implementation; documentation, skill installation and mechanical configuration checks follow their appropriate validators.
---

# Test-driven development

Adapted from the licensed reference-project skill; preserve `LICENSE`. Follow `AGENTS.md` and `docs/TESTING.md`, using pytest, Android JVM or QA instrumentation appropriate to the behavior rather than installing a test framework from an example.

## Red

Specify one observable behavior and identify the real production defect/change that should make its test fail. Cover invalid/boundary input and relevant failure paths as needed. Prefer assertions on behavior/state over implementation strings or mock call counts. Label unavoidable doubles and keep test-only helpers out of production code.

Run the new regression against the current behavior. Confirm it fails for the intended reason, not a typo, missing fixture or unrelated environment issue. Keep the RED evidence. For a behavior-preserving refactor, establish the existing behavior with tests; do not manufacture a failing assertion solely to perform a ritual.

## Green

Implement the smallest change that satisfies the behavior. Do not loosen expected results, mask errors, replace a real output with a fixture, or silently disable native/device checks. Preserve earlier attempts/data and unrelated work.

If implementation already exists, preserve it and establish regression evidence in a safe copy or reversible task-owned change. Never delete user code, working files or history simply because code was written before a test. If a trustworthy pre-fix run cannot be obtained, say so rather than inventing RED evidence.

## Refactor and hand off

Run relevant tests, then clean up while preserving behavior. Use `$spacefit-verification` for the repository-required gate and fresh per-group results. Report failures and unavailable prerequisites; targeted GREEN is not a passing full gate. Meaningful assertions matter more than a test count or coverage number.

For docs/skills or low-impact mechanical configuration changes, validate the changed artifact and links as required by the project rules. Do not add tests that merely duplicate generated text, and do not rerun hardware-heavy suites solely to install this skill.
