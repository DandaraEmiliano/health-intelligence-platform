# ADR 0005: Single PostgreSQL with separate schemas and pgvector

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

Two services need persistence: `core-api` (relational domain data) and `ai-service` (document chunks, embeddings, glossary, AI call metrics). ADR 0004 forbids `ai-service` from reading core data. The vector workload of a B2C patient is small: tens to low hundreds of documents per patient, each split into a modest number of chunks, and every query is filtered by one patient.

## Decision

Use **one PostgreSQL 16 instance with the `pgvector` extension and two schemas, each owned by its own database role**:

| Schema | Owner role | Migrations | Accessed by |
| --- | --- | --- | --- |
| `core` | `core_owner` (DDL) / `core_app` (DML) | Flyway in `core-api` | `core-api` only |
| `ai` | `ai_owner` (DDL) / `ai_app` (DML) | Alembic in `ai-service` | `ai-service` only |

- `core_app` and `ai_app` have **no privileges on each other's schema**. The isolation is enforced by grants, not by convention.
- No foreign key crosses schemas. The link is the opaque `patient_id` UUID.
- Migrations run with the owner role. Applications run with the least-privileged DML role.
- Embeddings use `vector(N)` with an **HNSW** index (cosine distance). `N` is fixed by the embedding model chosen in Phase 5, and changing the model means a new column or table plus re-indexing, recorded in an ADR.
- Every chunk query filters by `patient_id`, backed by a B-tree index. Row-level security on the `ai` schema is a second line of defence (ADR 0011).

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Dedicated vector database (Qdrant, Weaviate, Pinecone) | Purpose-built ANN features, scale | One more system to secure, back up and keep in sync with deletions; another processor of health data; not needed at this volume |
| Separate PostgreSQL instances per service | Strongest isolation, independent scaling | Double the operational cost locally and in AWS for no current benefit |
| Single schema shared by both services | Simplest | Breaks ADR 0004; any AI bug could read or write core data |

## Consequences

- One database to run locally and one RDS instance in AWS. Backups, encryption and point-in-time recovery cover both schemas.
- Splitting into two instances later is a configuration change plus a data move, because there are no cross-schema joins or foreign keys.
- Approximate nearest-neighbour search combined with a selective `patient_id` filter can return fewer results than requested. Phase 5 retrieval tests must cover this, and the mitigation (`hnsw.ef_search`, or iterative scans on pgvector versions that support them) must be measured, not assumed.
- The validated DDL proposal is in [`docs/architecture/ddl-v1-proposal.sql`](../architecture/ddl-v1-proposal.sql).
