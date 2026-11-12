# Architecture Decision Records

Each ADR records one decision: its context, the options considered, the choice and its consequences. ADRs are immutable once accepted; a change of mind is a new ADR that supersedes the old one.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-modular-monolith-and-separate-ai-service.md) | Modular monolith for the core, separate AI service | Proposed |
| [0002](0002-keycloak-as-identity-provider.md) | Keycloak as identity provider | Proposed |
| [0003](0003-asynchronous-extraction-via-outbox-and-sqs.md) | Asynchronous extraction via transactional outbox and SQS | Proposed |
| [0004](0004-core-as-source-of-truth-and-fact-pack.md) | Core as source of truth; core builds the AI fact pack | Proposed |
| [0005](0005-single-postgresql-with-separate-schemas-and-pgvector.md) | Single PostgreSQL with separate schemas and pgvector | Proposed |
| [0006](0006-mandatory-human-review-of-extracted-results.md) | Mandatory human review of extracted results | Proposed |
| [0007](0007-reference-range-as-value-object-no-unit-conversion.md) | Reference range as a value object; no unit conversion | Proposed |
| [0008](0008-llm-and-embedding-provider-abstraction.md) | LLM and embedding provider abstraction | Proposed |
| [0009](0009-api-conventions.md) | API conventions: RFC 9457, cursor pagination, idempotency keys | Proposed |
| [0010](0010-monorepo-with-versioned-contracts.md) | Monorepo with versioned contracts | Proposed |
| [0011](0011-patient-isolation-and-row-level-security.md) | Patient isolation in application code, RLS in the AI schema | Proposed |
| [0012](0012-open-source-local-aws-emulators.md) | Open-source local emulators for S3 and SQS (amends 0003) | Proposed |

Template: [`0000-template.md`](0000-template.md).
