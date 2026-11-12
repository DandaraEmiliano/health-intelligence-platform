# ADR 0010: Monorepo with versioned contracts

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

`core-api` and `ai-service` evolve together: a change in the extraction result usually touches the Python producer, the Java consumer, the JSON Schema and the tests at once. The team is small and reviews everything.

## Decision

- **One repository** containing `core-api/`, `ai-service/`, `contracts/`, `synthetic-data/`, `infra/` and `docs/`.
- **Contracts are first-class files** in `contracts/`: OpenAPI for HTTP, JSON Schema for queue messages, each with examples that CI validates (valid examples must pass, invalid ones must fail).
- **CI workflows are filtered by path** (`core-api/**`, `ai-service/**`, `contracts/**`), and a contract change triggers both services' contract tests.
- **One branch and one pull request per phase deliverable**, with the phase's acceptance criteria in the PR description.
- **Commit messages** follow Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`).

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| One repository per service | Independent histories and permissions | Cross-repository changes for every contract change; contract drift; more CI plumbing |
| Contracts in a separate repository or package | Explicit versioning | Release ceremony for every change with a single team |

## Consequences

- Atomic changes across services and simple local setup (one clone, one Compose file).
- The repository grows mixed tooling (Maven and Python), so each service keeps its own build files and the root holds no build logic.
- Splitting repositories later is possible if separate teams emerge.
