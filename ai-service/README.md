# ai-service

Private AI service (Python 3.12+, FastAPI). Extraction, embeddings, RAG and guardrails from Phase 5. Reachable only by `core-api`; never exposed publicly (ADR 0001, 0004).

## Run locally

Prerequisites: [uv](https://docs.astral.sh/uv/), Docker.

```bash
# from the repository root
cp .env.example .env
docker compose --profile infra up -d --wait

cd ai-service
set -a && . ../.env && set +a
uv sync
uv run alembic upgrade head          # runs as ai_owner
uv run uvicorn app.main:app --port 8081
```

- Liveness: http://localhost:8081/health/live
- Readiness (database reachable and schema migrated): http://localhost:8081/health/ready

## Configuration

Environment variables with the `AI_` prefix (`AI_DB_HOST`, `AI_DB_PORT`, `AI_DB_NAME`), plus `AI_APP_PASSWORD` and `AI_OWNER_PASSWORD`. No secrets in code (ADR 0008).

## Observability

- Prometheus metrics at `/metrics` (request count and latency per route template).
- Traces over OTLP when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (for example `http://localhost:4318`). See `docs/architecture/observability.md`.

## Extraction (Phase 5)

A worker started with the app consumes `document-extraction-requests`, and for each job:

1. downloads the PDF from S3 by key and checks its SHA-256 (`CHECKSUM_MISMATCH`, `DOCUMENT_NOT_FOUND`);
2. reads the text layer with pypdf (`PDF_ENCRYPTED`, `PDF_CORRUPTED`); a PDF without text is `UNSUPPORTED_SCANNED` and is never sent to the LLM;
3. asks Claude to transcribe the report into a strict JSON schema (`prompts/extraction/v1.md`, structured outputs, streaming, adaptive thinking, effort `high`, cached system prompt, server-side refusal fallback). The document text is escaped and delimited as data (prompt injection, threat model T03);
4. derives everything else in code (`app/extraction/numbers.py`, `dates.py`, `report.py`): decimal commas and thousands dots, comparators (`< 0,5`), range bounds and populations (only when unambiguous, ADR 0007), ISO dates, page count, and the validator warnings of ADR 0006. A result whose excerpt or value is not on the page it names gets `LOW_CONFIDENCE`;
5. publishes `document-extraction-result.v1` to `document-extraction-results` and only then deletes the request.

Transient provider errors (429, 5xx, network) leave the message on the queue for redelivery; on the last receive they are reported as `FAILED` with `retryable: true`. Logs carry ids and codes only, never document text or results.

| Variable | Default |
| --- | --- |
| `ANTHROPIC_API_KEY` | none (the SDK also accepts an `ant auth login` profile) |
| `AI_LLM_MODEL` / `AI_LLM_EFFORT` | `claude-opus-5-5` / `high` |
| `AI_EXTRACTION_WORKER_ENABLED` | `true` |
| `AWS_ENDPOINT_URL_S3`, `AWS_ENDPOINT_URL_SQS` | unset (set to the emulators locally) |

### Evaluation

```bash
uv run python -m app.extraction.evaluate            # all 30 text fixtures; calls the real provider (costs money)
uv run python -m app.extraction.evaluate --limit 3
```

Reports exact-match accuracy per field, per layout and per hard-case tag against `synthetic-data/fixtures`, and writes the mismatches to `eval-report.json` (ignored by git). The unit tests run the same pipeline with a fake provider that answers with the ground truth, which proves the deterministic part reproduces all 32 fixtures exactly.

## Patient isolation

Every query on patient data runs inside `app.db.patient_scope(engine, patient_id)`, which sets `app.patient_id` with transaction scope. Row-level security on `ai.document_chunk` fails closed without it (ADR 0011).

## Tests

```bash
uv run pytest                                   # unit tests only
AI_RUN_DB_TESTS=1 uv run pytest                 # plus database tests (needs the Compose database)
```

The database tests apply the Alembic migrations, check fail-closed RLS (fresh and pooled sessions), isolation for reads, writes and vector search, and the app role's restricted privileges, then roll the migrations back.
