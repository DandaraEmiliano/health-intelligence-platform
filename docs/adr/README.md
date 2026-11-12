# Architecture Decision Records

Each ADR records one decision: its context, the options considered, the choice and its consequences. ADRs are immutable once accepted; a change of mind is a new ADR that supersedes the old one.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-modular-monolith-and-separate-ai-service.md) | Modular monolith for the core, separate AI service | Proposed |
| [0002](0002-keycloak-as-identity-provider.md) | Keycloak as identity provider | Proposed |
| [0003](0003-asynchronous-extraction-via-outbox-and-sqs.md) | Asynchronous extraction via transactional outbox and SQS | Proposed |
| [0004](0004-core-as-source-of-truth-and-fact-pack.md) | Core as source of truth; core builds the AI fact pack | Proposed |
| 0005 | Single PostgreSQL with separate schemas and pgvector | Planned |
| 0006 | Mandatory human review of extracted results | Planned |
| 0007 | Reference range as a value object; no unit conversion | Planned |
| 0008 | LLM and embedding provider abstraction | Planned |
| 0009 | API conventions: RFC 9457, cursor pagination, idempotency keys | Planned |
| 0010 | Monorepo with versioned contracts | Planned |
| 0011 | Patient isolation in application code, RLS in the AI schema | Planned |

Template: [`0000-template.md`](0000-template.md).
