# ADR 0001: Modular monolith for the core, separate AI service

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

The MVP has about ten business capabilities (identity, consent, patient, health records, documents, laboratory, timeline, analytics, AI assistant, notifications, audit) and is built by a very small team. Most capabilities share one consistency boundary, the patient, and many operations touch several of them in one transaction (for example, verifying a laboratory report changes results, emits an audit event and triggers a notification).

The AI workload is different in nature: a different language ecosystem (Python for PDF processing, embeddings and LLM tooling), a different failure profile (slow, rate-limited, non-deterministic external calls), a different scaling profile, and a faster release cadence for prompts and evaluation.

## Decision

1. Build the core as a **modular monolith** (`core-api`, Java 21+, Spring Boot) with module boundaries enforced by **Spring Modulith** and verified in CI (`ApplicationModules.verify()` plus ArchUnit rules).
2. Build the AI capabilities as a **separate service** (`ai-service`, Python, FastAPI) that is never exposed publicly and has no access to the core database schema.

Module rules inside `core-api`:

- Each module exposes a public `api` package (interfaces, DTOs, events). Everything else is internal.
- Modules talk to each other through the public API for queries and through **application events** (persisted in Spring Modulith's Event Publication Registry) for side effects.
- Clean Architecture layering (`domain`, `application`, `infrastructure`) is applied only inside modules with rich domain logic (`documents`, `laboratory`, `assistant`). CRUD-style modules (`healthrecords`, `notifications`) use two layers. Layers are added where they pay for themselves, not uniformly.

Initial modules: `shared`, `identity`, `consent`, `patient`, `healthrecords`, `documents`, `laboratory`, `timeline`, `analytics`, `assistant`, `notifications`, `audit`.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Microservices per capability | Independent deploys and scaling | Distributed transactions, network failure modes and operational cost with no scaling requirement to justify them; much slower delivery for a small team |
| Monolith without enforced boundaries | Fastest start | Boundaries erode quickly; extracting a module later becomes a rewrite |
| Everything in Java, including AI | One stack, one deploy | The Python ecosystem for PDF parsing, embeddings and LLM tooling is significantly richer; AI release cadence would be coupled to the core |
| Everything in Python | One stack | Loses the Spring ecosystem for security, transactions and modularity; does not match the team's main strength |

## Consequences

- One deployable for the core: local transactions, simple debugging, one database migration stream.
- Boundary violations fail the build, which keeps the option of extracting a module into a service later.
- The Java ↔ Python boundary requires explicit, versioned contracts (see ADR 0003 and `contracts/`) and contract tests.
- Two runtimes to operate, observe and secure. Distributed tracing must cross the boundary (OpenTelemetry context propagation over HTTP and SQS message attributes).
