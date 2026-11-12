# ADR 0003: Asynchronous extraction via transactional outbox and SQS

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

Extracting structured results from a laboratory PDF takes seconds to minutes, depends on an external LLM provider that can be slow, rate-limited or unavailable, and must be retried safely. The upload request cannot wait for it.

When a document is uploaded, two things must both happen or neither: the database records the document, and the extraction job is requested. Writing to the database and publishing to a queue in the same code path, without coordination, loses or duplicates jobs on failure (the dual-write problem).

The AI assistant chat is different: the user is waiting for an answer.

## Decision

**Extraction and indexing are asynchronous, over SQS, with a transactional outbox. The chat is synchronous, over internal HTTP.**

Extraction flow:

1. `documents` stores the PDF in S3, then, in **one database transaction**, inserts the `health_document` row, an `extraction_job` row and an outbox event `DocumentUploaded`.
2. An outbox relay publishes the event to the `document-extraction-requests` queue and marks it as published. Delivery is at-least-once.
3. `ai-service` consumes the request, reads the PDF from S3 and publishes a `DocumentExtractionResult` to `document-extraction-results`.
4. `laboratory` consumes the result, validates it against the JSON Schema and persists the report and results in `PENDING_REVIEW`.

Rules:

- **Idempotency** on every consumer: `jobId` identifies a job, and the `(document_id, extractor_version)` unique constraint prevents duplicate jobs. Redelivered messages are no-ops.
- **Retries** with exponential backoff for transient failures; after a maximum number of receives the message goes to a **dead-letter queue**, and the document becomes `FAILED` with a reason code.
- **Message contracts** are versioned JSON Schemas in `contracts/events/` (`schemaVersion` field in every message). Both sides validate on read and in CI.
- **S3 access by key, not by pre-signed URL.** The request carries `bucket` and `objectKey`. `ai-service` has read-only `s3:GetObject` permission on the documents prefix. A pre-signed URL in a queued message could expire before the message is consumed (queue backlog, retries, DLQ redrive).
- **Messages carry health data** (result values and source excerpts). Queues are encrypted at rest with KMS in AWS, and message bodies are never logged.
- **Tracing**: the OpenTelemetry context is propagated through SQS message attributes.
- **Chat**: `POST /internal/v1/assistant/answers`, with a timeout and a circuit breaker (Resilience4j) on the core side. Server-sent events for streaming are a later addition.

Locally, SQS and S3 are provided by LocalStack.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Synchronous extraction during upload | Simplest | Long requests, timeouts, no retries, user waits for the LLM |
| Kafka | Replay, ordering, high throughput | No streaming or replay requirement; much higher operational cost; revisit only with a concrete need |
| Redis Streams | Already in the stack | Weaker durability guarantees and no managed DLQ semantics; Redis is a cache here, not a system of record |
| Direct publish without outbox | Less code | Dual-write: jobs lost or duplicated on partial failure |
| `ai-service` writes results directly into the core database | No result queue | Breaks module ownership and ADR 0004; couples the AI service to the core schema |

## Consequences

- Upload returns `202 Accepted` quickly. Status is visible through the document resource and in-app notifications.
- Consumers must be written idempotent from day one, and tests must cover redelivery and failure injection.
- The outbox relay is a moving part to observe (lag and unpublished count metrics).
- A DLQ redrive procedure is needed in the runbooks.
