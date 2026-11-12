# ADR 0008: LLM and embedding provider abstraction

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

`ai-service` calls an LLM for structured extraction and for answers, and an embedding model for retrieval. Which provider is used depends on quality in Portuguese, cost, data processing terms (retention, region, DPA) and LGPD analysis of international transfers, which is still open (decision D3). Tests must run without network access or credentials. Prompt changes must be traceable, because they change behaviour as much as code does.

## Decision

1. **A thin in-house interface**, not a framework:
   - `LlmProvider.generate_structured(prompt: RenderedPrompt, output_model: type[BaseModel], params) -> StructuredResult[T]`. It returns the validated Pydantic object plus usage (model, tokens, latency).
   - `EmbeddingProvider.embed(texts: list[str], purpose: Literal["document", "query"]) -> EmbeddingBatch`. It returns vectors plus the model name and dimension.
2. **Implementations**: one commercial provider (chosen in D3), a local provider (Ollama) for offline development, and a deterministic **fake provider** used by unit and contract tests.
3. **Selection by configuration** (environment variables). API keys come only from the environment or a secret manager, never from code or the repository.
4. **Structured output is mandatory**: every LLM call declares a Pydantic output model. Invalid output is retried once with the validation error, then fails with `OUTPUT_SCHEMA_VIOLATION`.
5. **Prompts are versioned files** in `ai-service/prompts/` (for example `extraction/v1.md`). The version is recorded in every call log, every extraction result (`usage.promptVersion`) and every assistant message.
6. **Untrusted content is always delimited.** Document text and retrieved chunks go inside explicit data delimiters with an instruction that their content is data, never instructions (mitigation for indirect prompt injection; see the threat model).
7. Resilience: timeouts, bounded retries with jitter for transient errors, and a per-provider concurrency limit.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Call one provider's SDK directly | Least code | Locks in the provider; tests need mocks of SDK internals |
| LangChain or LlamaIndex | Many integrations | Large abstraction surface and frequent API changes; the features we need (structured output, embeddings, retries) are small enough to own |
| Spring AI in the Java core | One stack | Contradicts ADR 0001: AI logic stays in `ai-service` |

## Consequences

- Adding a provider means implementing two small interfaces and passing the shared contract test suite.
- Evaluation results are always tied to a provider, model and prompt version, so regressions can be traced.
- The final choice of default provider and embedding model is made in Phase 5 by benchmark on the synthetic golden set, and recorded in a follow-up ADR.
