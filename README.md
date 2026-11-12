# Health Intelligence Platform

A healthcare platform that turns fragmented laboratory reports (PDF) into a structured, patient-verified health history, with an AI assistant that explains results using only the patient's own verified data.

> **Status:** Phase 2 — Architecture (in review). No application code yet.
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

## Repository layout

```text
docs/
  adr/                 Architecture Decision Records
  architecture/        Diagrams and module map (Phase 2)
  security/            Threat model and privacy requirements map (Phase 2)
contracts/
  events/              JSON Schemas for queue messages between core-api and ai-service
core-api/              Java service (Phase 3)
ai-service/            Python service (Phase 3)
synthetic-data/        Synthetic lab report generator and ground truth (Phase 3)
infra/                 Docker Compose, observability, Terraform (Phases 3 and 7)
```

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 1. Product Discovery | MVP scope, requirements, user journeys, backlog | Approved |
| 2. Architecture | ADRs, contracts, DDL v1, OpenAPI draft, threat model | In progress |
| 3. Project Foundation | Compose, service skeletons, CI, synthetic dataset | Planned |
| 4. Core Backend | Identity, patient, documents, laboratory results, timeline, audit | Planned |
| 5. AI Engineering | Extraction, RAG, assistant, guardrails, evaluation | Planned |
| 6. Analytics and Notifications | Trends, comparisons, in-app notifications | Planned |
| 7. Production Readiness | AWS (Terraform), CD, performance, security review | Planned |

## Compliance disclaimer

The security and privacy controls in this project are engineering measures. They do **not** by themselves make the platform compliant with LGPD, ANVISA (SaMD) or any other regulation. Legal bases, consent wording, international data transfers, retention periods and regulatory classification require validation by qualified legal and regulatory specialists.
