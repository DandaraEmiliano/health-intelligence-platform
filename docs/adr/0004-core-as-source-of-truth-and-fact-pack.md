# ADR 0004: Core as source of truth; core builds the AI fact pack

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

The AI assistant answers questions about a patient's own health data: "explain my latest blood test", "what changed between my last two exams", "which results were outside the laboratory reference range". It must use only authorized data, never invent results, and distinguish extracted facts from generated explanations.

Two kinds of questions exist:

- **Structured questions** (latest values, changes, out-of-range results) have exact answers in the relational data. Vector similarity is the wrong tool for them: it can miss rows or retrieve the wrong date.
- **Unstructured questions** (what does this marker mean, what does the report's note say) benefit from semantic retrieval over document text and a curated glossary.

The biggest risks are cross-patient leakage and hallucinated numbers.

## Decision

**`core-api` is the single source of truth and the only place where authorization happens. For each question, the core builds a fact pack of verified, authorized data and sends it to `ai-service`. `ai-service` never reads the core schema and never calls the core back.**

- The `assistant` module resolves the patient from the token, checks AI consent and rate limits, and builds the **fact pack**: verified results relevant to the question (for the MVP: results from the latest reports plus any analyte named in the question), the patient's declared allergies, medications and conditions when relevant, and report metadata. Each fact carries a stable ID so it can be cited.
- `ai-service` adds **semantic retrieval** over (a) chunks of the same patient's verified documents and (b) a curated public glossary. Every query on patient chunks requires `patient_id`. The repository function has no code path without it, and row-level security on the `ai` schema enforces it again at the database level.
- The response is structured: facts cited by ID, generated explanations kept separate, uncertainty, an `insufficientData` flag, and care-seeking recommendations.
- **Post-generation check:** every number in the answer must match a value in the fact pack or in the retrieved chunks. Otherwise the answer is rejected and regenerated once, then fails safely. This check is possible only because the set of facts is closed and known.
- Conversations and messages are stored in the core (`assistant` module), under the same authorization, retention and deletion rules as other patient data. `ai-service` keeps only call metrics (tokens, latency, model, prompt version), not conversation text.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| `ai-service` reads the core database (read-only role) | No fact-pack building in Java | Authorization duplicated in two languages; schema coupling; one more path to leak data |
| Tool calling: `ai-service` calls core endpoints with a delegated, short-lived token | The model fetches exactly what it needs; scales to large histories | More moving parts, harder to test, more round trips; unnecessary at B2C data volumes. **Kept as the planned evolution** if fact packs outgrow the context window |
| Vector search only, over everything including results | One mechanism | Unreliable for exact, dated, numeric questions; makes hallucination checks weak |

## Consequences

- Authorization logic lives in one place and is tested once.
- The core must choose a sensible fact-pack slice. The selection rules are part of the `assistant` module and are covered by tests.
- Fact-pack size is bounded and monitored (token count per request). Crossing a threshold triggers the tool-calling evolution, recorded in a new ADR.
- Indexing for semantic retrieval happens only after a report is verified (`LaboratoryReportVerified` event), so unverified data never reaches the AI.
- Revoking AI consent or deleting the account sends a purge message that removes all the patient's chunks from the `ai` schema.
