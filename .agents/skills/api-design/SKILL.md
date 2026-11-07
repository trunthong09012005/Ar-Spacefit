---
name: api-design
description: Design or review AR-SpaceFit REST resource paths, HTTP semantics, validation, errors, pagination, idempotency and compatibility with the Android client. Use for API contract changes; implementation and job lifecycle remain owned by spacefit-backend.
metadata:
  origin: ECC; adapted for AR-SpaceFit from the user's reference project
---

# API design for AR-SpaceFit

Adapted from the licensed reference-project skill; preserve `LICENSE`. Read `AGENTS.md`, existing endpoints/models/tests in `backend/spacefit/`, and the Android consumer in `apps/android/src/main/java/com/example/reconstruction/` before proposing a contract.

## Preserve an explicit contract

Use resource-oriented paths and appropriate HTTP methods/status codes. GET must not trigger reconstruction or mutation. Distinguish validation, missing authentication, missing resource, state conflict and unexpected server errors. Keep responses consistent with existing clients; do not add `/api/v1`, a response envelope or rename fields simply to satisfy a generic convention.

For each change specify request fields, required/optional values, units, invalid inputs, response fields and observable error behavior. Keep unscaled previews distinct from confirmed metre-based models. Validate finite numbers and bounds; use centimetres only where the public/UI contract says so.

For creates/retries, define idempotency across lost replies and repeated taps. Expose safe status/progress/error information without filesystem paths, SQL errors, raw native logs or tokens. An accepted job is not a successful reconstruction; readiness is startup readiness, not a model-quality guarantee.

Add pagination/filtering when the data/use case warrants it; use a deterministic sort and validate supported limits/filters. Do not invent multi-tenancy, role models, rate limits or service dependencies without a requirement. Authentication/authorization and upload limits must remain enforced for the actual deployment model.

## Compatibility and evidence

Classify breaking versus additive changes and update Android decoding, validation and migration behavior as relevant. Do not infer compatibility merely because a field is optional on the server. Review OpenAPI generated from FastAPI against real HTTP behavior.

Use `$spacefit-backend` for implementation and `$spacefit-verification` for contract/HTTP/client integration evidence. Test success plus malformed input, auth failures, state conflicts and relevant retry/download cases with QA data and fake tokens. Examples from other frameworks are design references, not instructions to install Django/Go/Node or replace FastAPI.
