---
name: systematic-debugging
description: Investigate AR-SpaceFit bugs, failed tests, unexpected output or environment failures using reproduction, boundary evidence and small hypothesis-driven checks before implementing a fix. Use with the relevant Android/backend/Gen3D skill.
---

# Systematic debugging

Adapted from the licensed reference-project skill; preserve `LICENSE`. Read the project rules and current work status. A request to diagnose authorizes investigation, not an unrelated implementation or live data mutation.

## Establish the failure

Read the exact error and identify the artifact, version, component and time involved. Reproduce with a bounded QA case where possible. Inspect recent relevant changes and distinguish a current observation from a historical report. If not reproducible, gather the specific evidence needed instead of guessing a repair.

Trace boundaries such as Android → HTTP → SQLite/job worker → Docker → native tool → GLB → viewer. Compare input/output shape, state and ownership at the suspect boundary. Use fake tokens and authorized artifacts; do not dump environment variables, credentials, full uploads, private logs or the real DB. Add only the diagnostics necessary to discriminate hypotheses.

## Test a hypothesis

Compare a working path or fixture with the failing one, including version/configuration differences. State a specific hypothesis and the observation that would support or reject it. Change one relevant variable in a disposable/reversible scope; keep the resulting evidence.

If the observation rejects the hypothesis, revise the explanation rather than stacking speculative fixes. Several failed attempts should prompt a reassessment of assumptions and boundaries. Ask for a missing decision only when it materially blocks progress; do not convert a fixed attempt count into automatic permission for an architectural rewrite.

## Implement and verify when requested

Create a meaningful regression first using `$test-driven-development`, make the smallest justified fix and apply `$spacefit-verification`. Match the verification to the original symptom; a native fixture or successful file load is not proof that a real scan looks correct.

For environment/timing failures with incomplete causality, state what was observed and what remains unknown. A bounded mitigation may be appropriate within the task; do not present it as a proven root cause. Preserve unrelated user work and original scan inputs throughout.
