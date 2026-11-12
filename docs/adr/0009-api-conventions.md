# ADR 0009: API conventions: RFC 9457 errors, cursor pagination, idempotency keys

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

The public API is consumed by a web client now and possibly by mobile apps and partner integrations later. Patient data must not leak through identifiers (IDOR). Uploads and AI messages are expensive, non-idempotent operations that clients may retry on network errors. Timelines grow over time and are read newest first.

## Decision

- **Versioning in the path**: `/api/v1/...`. A breaking change means `/api/v2` for the affected resources.
- **Patient scope comes from the token, never from the path.** Patient resources live under `/patients/me/...` or are implicitly scoped (`/documents`, `/laboratory-reports`). A resource that belongs to another patient returns **404**, not 403, so its existence is not revealed.
- **Success bodies are the resource itself**, with no generic envelope. Lists return `{ "items": [...], "nextCursor": "..." | null }`.
- **Errors use RFC 9457 Problem Details** (`application/problem+json`, Spring's `ProblemDetail`), extended with a stable `code` (for example `DOCUMENT_TOO_LARGE`), a `correlationId`, and `errors[]` for field validation. Messages never contain health data.
- **Cursor pagination** (keyset on `(sort_timestamp, id)`), opaque Base64URL cursors, default page size 20, maximum 100. No offset pagination on growing collections.
- **Idempotency**: `Idempotency-Key` header (UUID) accepted on `POST /documents` and `POST /ai/conversations/{id}/messages`. The first response is stored in Redis for 24 hours, keyed by user and key. The same key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`.
- **Optimistic concurrency**: `ETag` on editable resources and `If-Match` required on review and profile updates (412 on mismatch, 428 if missing on review).
- **Dates**: ISO 8601. Instants in UTC (`2026-11-12T22:40:00Z`). Clinical dates such as collection date as `date`.
- **Rate limits**: 429 with `Retry-After`.
- **Contract**: OpenAPI 3.1 in `contracts/core-api.openapi.yaml` is the design source in Phase 2. From Phase 3 the springdoc-generated spec is compared against it in CI.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Envelope `{success, data, error}` | Uniform shape | Duplicates HTTP semantics; non-standard |
| Offset pagination | Simple, random access | Unstable results while data changes; slow at depth |
| `patientId` in paths with authorization checks | Ready for professionals later | Easier to get wrong; for the MVP, `me` removes the class of bug entirely. Professional access gets its own resources later (`/patients/{id}` guarded by access grants) |

## Consequences

- Clients handle one error format.
- Idempotency needs Redis from Phase 4.
- When professionals arrive, new endpoints are added. The patient endpoints do not change meaning.
