# Health Intelligence Platform

A healthcare platform that turns fragmented laboratory reports (PDF) into a structured, patient-verified health history, with an AI assistant that explains results using only the patient's own verified data.

> **Live demo:** https://hip-app.onrender.com (synthetic data only; the first page can take a minute to wake up). Create an account, download an example report on the Documentos page and upload it to see the timeline.
>
> **Status:** Phases 1–7 done. A free demo runs on Render + Neon with the AI turned off. The AWS infrastructure is written and validated in CI but never applied, and the AI has only been tested with a fake provider (see *What is left*).
>
> **Data policy:** this repository uses **synthetic data only**. Never commit real patient data, real lab reports or credentials.

## Core principles

- **AI assists understanding; it does not diagnose, prescribe or change dosages.**
- **Nothing extracted is trusted until the patient verifies it.** Only verified results feed analytics and the AI assistant.
- **One patient never sees another patient's data.** Isolation is enforced by design and by automated tests that block merges.
- **Lab reference ranges are preserved as printed**, together with their original units. They are not treated as universal diagnostic thresholds.

## Architecture at a glance

| Component | Responsibility | Stack |
| --- | --- | --- |
| `web` | Patient SPA; never holds a token | React, TypeScript, Vite, nginx (CSP) |
| `bff` | Backend-for-Frontend: sign-in, HttpOnly session, token relay to core-api (ADR 0014) | Java 25, Spring Boot, Spring Session Redis |
| `core-api` | Modular monolith: authorization, domain, persistence, public API, AI conversations | Java 25, Spring Boot, Spring Modulith, PostgreSQL, Flyway |
| `ai-service` | PDF extraction, validation, embeddings, RAG, guardrails, LLM calls (private, never public) | Python 3.12, FastAPI, Pydantic, pgvector |
| Keycloak | Identity provider (OIDC), MFA, service-to-service tokens | Keycloak |
| Infrastructure | Object storage, queues, cache, observability | S3, SQS (S3Mock and ElasticMQ locally), Redis, OpenTelemetry, Prometheus, Grafana; AWS as Terraform in [`infra/terraform`](infra/terraform) |

See [`docs/adr/`](docs/adr/) for the reasoning behind each decision.

## Getting started

**One command** (macOS or Linux):

```bash
./scripts/dev.sh          # checks prerequisites, starts infrastructure and all services, prints the URLs
./scripts/dev.sh status   # what is running
./scripts/dev.sh logs core-api
./scripts/dev.sh stop
```

It uses Java 25 only for core-api, so other projects keep their own Java version.

**As containers** (no JDK, Python or Node needed), the way they will run in AWS: non-root, read-only file systems, no Linux capabilities, CPU and memory limits on the service that parses PDFs.

```bash
cp .env.example .env
docker compose --profile infra --profile apps up -d --build --wait
```

The web is served by nginx on http://localhost:5173 with a strict Content Security Policy; stop `scripts/dev.sh` first (same ports). ai-service has no published port: only core-api reaches it.

**Manually**, step by step:

Prerequisites: Docker, JDK 25 + Maven 3.9 (core-api), Python 3.12 + [uv](https://docs.astral.sh/uv/) (ai-service), Node.js 22+ (web).

```bash
cp .env.example .env                              # local-only values
docker compose --profile infra up -d --wait       # PostgreSQL + pgvector, Redis, Keycloak, S3, SQS
infra/docker/smoke-test.sh                        # optional: verifies every infrastructure service
# optional telemetry stack (Grafana, Prometheus, Tempo): see docs/architecture/observability.md
docker compose --profile infra --profile observability up -d --wait
```

Then run each service as described in [`core-api/README.md`](core-api/README.md), [`ai-service/README.md`](ai-service/README.md) and [`web/README.md`](web/README.md).

| Service | Local URL |
| --- | --- |
| web | http://localhost:5173 |
| core-api | http://localhost:8080 |
| ai-service | http://localhost:8081 |
| Keycloak (realm `hip`) | http://localhost:8180 |
| PostgreSQL | localhost:5432 (db `hip`) |
| S3 (S3Mock) | http://localhost:9090 |
| SQS (ElasticMQ) | http://localhost:9324 |
| Grafana (profile `observability`) | http://localhost:3000 |

### Free demo on Render

**Live at https://hip-app.onrender.com.** A public demo with synthetic data, at no cost: Render free web services plus Neon PostgreSQL, from [`render.yaml`](render.yaml). It is a `render` profile with the same code: built-in sign-in instead of Keycloak and the BFF, PDFs in PostgreSQL, and HTTP instead of SQS (ADR 0015). Step by step: [`docs/deploy/render.md`](docs/deploy/render.md).

## Repository layout

```text
docs/
  adr/                 Architecture Decision Records (0001-0011)
  architecture/        Container view, DDL v1 proposal and its validation
  security/            Threat model (STRIDE) and LGPD requirements map
contracts/
  core-api.openapi.yaml  Public API design (OpenAPI 3.1)
  events/              JSON Schemas for queue messages between core-api and ai-service
core-api/              Java service: Spring Boot modular monolith
ai-service/            Python service: FastAPI, Alembic
web/                   Patient web app: React, TypeScript, Vite
synthetic-data/        Deterministic synthetic lab report PDFs with ground truth (32 fixtures, 4 layouts)
infra/                 Compose support files (Postgres init, Keycloak realm, SQS queues, observability stack), smoke tests
```

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 1. Product Discovery | MVP scope, requirements, user journeys, backlog | Approved |
| 2. Architecture | ADRs, contracts, DDL v1, OpenAPI draft, threat model | Done |
| 3. Project Foundation | Compose, service skeletons, CI, synthetic dataset, web skeleton, observability | Done |
| 4. Core Backend | Identity, patient, documents, laboratory results, timeline, audit | Done |
| 5. AI Engineering | Extraction, assistant, guardrails, evaluation set | Done (fake provider only; real-model evaluation pending) |
| 6. Analytics and Notifications | Trends, out-of-range, comparisons, in-app notifications | Done |
| 7. Production Readiness | LGPD export, hardened images, CSP, BFF, AWS Terraform, gated CD | Done locally; never applied to AWS |

### What is left

1. **Real-model evaluation.** Put `ANTHROPIC_API_KEY` in `.env` and run the extraction evaluation and `ai-service/evals/assistant-safety.jsonl`.
2. **Before AWS.** Everything listed in [`infra/terraform/README.md`](infra/terraform/README.md): account and state bucket, domain and certificate, where Keycloak runs, secret values, database bootstrap, RDS CA for `verify-full`, and the legal decisions (transfer to the LLM provider, retention).
3. **Legal and regulatory validation.** The items marked in [`docs/security/privacy-requirements-map.md`](docs/security/privacy-requirements-map.md), and whether the product falls under ANVISA's medical-device software rules.

## Compliance disclaimer

The security and privacy controls in this project are engineering measures. They do **not** by themselves make the platform compliant with LGPD, ANVISA (SaMD) or any other regulation. Legal bases, consent wording, international data transfers, retention periods and regulatory classification require validation by qualified legal and regulatory specialists.
