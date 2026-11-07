---
name: verification-before-completion
description: Check that AR-SpaceFit completion, fix, build and test claims are supported by fresh evidence for the changed artifact before handoff. Use to review evidence; spacefit-verification owns test execution and acceptance requirements.
---

# Evidence before completion

Adapted from the licensed reference-project skill; preserve `LICENSE`. Identify the exact claim, choose the check that establishes it, run/read that check and its exit status, then report the supported result and remaining gaps.

## Match the evidence to the claim

| Claim | Evidence needed |
|---|---|
| Test group passed | Fresh relevant results and failure/error/skip counts |
| Build works | Successful build of the changed module/artifact |
| Regression is covered | Meaningful failing pre-fix case plus post-fix result, or explicit limitation |
| UI renders correctly | Actual frame/pixel and interaction evidence for the affected view |
| Native pipeline works | Real required startup and native-stage checks, distinct from mocks |
| Real model has correct dimensions/quality | Actual model/object inspection and independent measurements |
| Skill/config installed | Validate installed files, routing, metadata and relevant links |

Use `$spacefit-verification` for test selection, isolation, runner flags and reports. Do not substitute lint for compilation, a loaded callback for visible pixels, startup for reconstruction quality or local tests for remote CI/deployment.

Read the complete relevant result, including failures. Keep independent runs separate and use results from the changed revision; rerun when later changes affect their validity. Do not repeat expensive checks merely to make a fresh conversational claim when the already obtained evidence still applies to the unchanged artifact.

Historical evidence may be reported with its date and limitations; it must not be relabeled as a current pass. Report unexecuted/skipped/blocked checks explicitly. Skill or documentation validation establishes only that configuration's integrity, not runtime product correctness. Keep user-data/secret constraints while collecting evidence and update the repository's work/test reports.
