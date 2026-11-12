# ADR 0011: Patient isolation in application code, RLS in the AI schema

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

"Never expose one patient's information to another patient" is the most important security requirement. Isolation can be enforced in application code, in the database with PostgreSQL row-level security (RLS), or both.

RLS in `core-api` (JPA with a shared connection pool) requires setting a session variable for each transaction (`SET LOCAL app.patient_id`), handling background jobs and admin flows that legitimately cross patients, and testing that every code path sets it. In `ai-service` the risk is concentrated in a few vector queries. A retrieval bug there (a forgotten filter, a refactor of the query builder) would leak another patient's text straight into an LLM answer.

## Decision

**Core (`core` schema): isolation in application code, enforced by design and by tests.**

- Every clinical table carries `patient_id` (denormalized onto child tables too).
- The current patient is resolved once per request from the token (`CurrentPatient`) and passed to repositories. Repository methods for clinical data take `PatientId` as a required parameter, and there are no `findById(UUID)` methods for patient-owned aggregates.
- Patient endpoints never accept a patient ID from the client (ADR 0009).
- An **authorization test suite** creates two patients and, for every endpoint, asserts that patient B cannot read, list, modify or infer patient A's data. It blocks merges.
- ArchUnit rule: controllers in clinical modules may not call repository methods that lack a `PatientId` parameter.

**AI (`ai` schema): application filter plus RLS as defence in depth.**

- Every chunk query requires `patient_id`. The repository API has no other path.
- RLS is enabled and **forced** on `ai.document_chunk`. The policy compares `patient_id` with `current_setting('app.patient_id')`. If the variable is not set, `current_setting` raises an error, so a forgotten setting fails closed and returns nothing.
- `ai-service` sets `app.patient_id` with `SET LOCAL` inside the transaction of each request or job.
- The purge job (account deletion, consent revocation) also runs scoped to one patient at a time.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| RLS everywhere, including the core | Database-enforced isolation for all data | High complexity with JPA and pooling now; reconsider when multi-tenant access grants arrive |
| Application checks only, everywhere | Simplest | A single missed filter in vector search leaks data directly into LLM output |
| One database or schema per patient | Physical isolation | Operationally absurd at B2C scale |

## Consequences

- The DDL proposal enables and forces RLS on `ai.document_chunk`, and its validation script proves three cases: no patient set fails, patient A sees only A's rows, and an insert for another patient is rejected.
- Finding from Phase 3 tests: once a pooled session has run a scoped transaction, PostgreSQL keeps `app.patient_id` defined as an empty string for that session. The policy's `::uuid` cast then raises `invalid input syntax for type uuid` instead of "unrecognized configuration parameter". Both outcomes fail closed (an error, never a silently empty or wider result), and `ai-service/tests/test_patient_isolation.py` covers both.
- The core relies on discipline plus automated tests. The ArchUnit rule and the two-patient test suite are part of the Phase 4 definition of done.
- Revisit RLS for the core when professional and organization access (`access_grant`) is introduced.
