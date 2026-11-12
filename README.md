# Health Intelligence Platform

A healthcare platform that turns fragmented laboratory reports (PDF) into a structured, patient-verified health history, with an AI assistant that explains results using only the patient's own verified data.

> **Status:** Phase 3 — Project Foundation (in progress).
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
| `core-api` | Modular monolith: authorization, domain, persistence, public API, AI conversations | Java 21+, Spring Boot, Spring Modulith, PostgreSQL, Flyway |
| `ai-service` | PDF extraction, validation, embeddings, RAG, guardrails, LLM calls (private, never public) | Python 3.12, FastAPI, Pydantic, pgvector |
| Keycloak | Identity provider (OIDC), MFA, service-to-service tokens | Keycloak |
| Infrastructure | Object storage, queues, cache, observability | S3, SQS (LocalStack locally), Redis, OpenTelemetry, Prometheus, Grafana |

See [`docs/adr/`](docs/adr/) for the reasoning behind each decision.

## Getting started

Prerequisites: Docker, JDK 25 + Maven 3.9 (core-api), Python 3.12 + [uv](https://docs.astral.sh/uv/) (ai-service), Node.js 22+ (web).

```bash
cp .env.example .env                              # local-only values
docker compose --profile infra up -d --wait       # PostgreSQL + pgvector, Redis, Keycloak, S3, SQS
infra/docker/smoke-test.sh                        # optional: verifies every infrastructure service
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
infra/                 Compose support files (Postgres init, Keycloak realm, SQS queues), smoke test
```

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 1. Product Discovery | MVP scope, requirements, user journeys, backlog | Approved |
| 2. Architecture | ADRs, contracts, DDL v1, OpenAPI draft, threat model | Done |
| 3. Project Foundation | Compose, service skeletons, CI, synthetic dataset | In progress |
| 4. Core Backend | Identity, patient, documents, laboratory results, timeline, audit | Planned |
| 5. AI Engineering | Extraction, RAG, assistant, guardrails, evaluation | Planned |
| 6. Analytics and Notifications | Trends, comparisons, in-app notifications | Planned |
| 7. Production Readiness | AWS (Terraform), CD, performance, security review | Planned |

## Compliance disclaimer

The security and privacy controls in this project are engineering measures. They do **not** by themselves make the platform compliant with LGPD, ANVISA (SaMD) or any other regulation. Legal bases, consent wording, international data transfers, retention periods and regulatory classification require validation by qualified legal and regulatory specialists.
