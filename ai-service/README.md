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

## Patient isolation

Every query on patient data runs inside `app.db.patient_scope(engine, patient_id)`, which sets `app.patient_id` with transaction scope. Row-level security on `ai.document_chunk` fails closed without it (ADR 0011).

## Tests

```bash
uv run pytest                                   # unit tests only
AI_RUN_DB_TESTS=1 uv run pytest                 # plus database tests (needs the Compose database)
```

The database tests apply the Alembic migrations, check fail-closed RLS (fresh and pooled sessions), isolation for reads, writes and vector search, and the app role's restricted privileges, then roll the migrations back.
