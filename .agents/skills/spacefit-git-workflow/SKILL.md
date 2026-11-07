---
name: spacefit-git-workflow
description: Handle AR-SpaceFit Git change review, branches, merge conflicts and preparation of focused commits or pull requests when requested. Use for version-control tasks; exclude ordinary edits and external publishing without authorization.
---

# AR-SpaceFit Git workflow

Read `AGENTS.md` and `CONTRIBUTING.md`. Inspect current branch, `git status --short`, staged/unstaged diffs and unmerged files before mutating repository state. Existing edits belong to the user; preserve unrelated work.

## Scope the operation

For review/status requests, inspect and report. For a requested merge/conflict resolution, identify both sides and preserve intended behavior rather than selecting ours/theirs wholesale. Validate affected behavior and remove conflict markers without hiding unfinished decisions. Before pulling/rebasing, determine whether local work or shared history would be overwritten and use a reversible, scoped approach.

When asked to commit, stage explicit reviewed files and inspect the staged diff. Avoid `git add .` in this repository's mixed worktree: earlier moves may appear as deletions plus new paths until staged, and much code may remain untracked. Keep secrets, keystores, real data, native binaries and QA output excluded. An ignored file is not automatically safe to publish.

## GitHub

Verify the target repository/branch/PR. Use `$gh-address-comments` for requested review fixes and `$gh-fix-ci` for failing Actions checks. CLI presence does not prove authentication or access; do not print credentials or initiate interactive login without user involvement.

Commit, push, PR creation/edit/comment, merge and branch deletion are separate operations: perform those included in the authorized workflow. Installing this skill grants none of those actions. Do not force-push or rewrite shared history without an explicit request and an established exact target.

Report actual verification and commit/PR identifiers if created. Do not infer successful remote CI or merge from local test results.
