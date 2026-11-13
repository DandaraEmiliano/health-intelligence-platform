# ADR 0013: Web client as an SPA with in-memory tokens; BFF deferred

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)
- **Resolves:** decision D1 (frontend) of the Phase 1 document

## Context

The review of extracted results (ADR 0006) is the core user journey and needs a real interface. The product is behind login, so it needs no SEO or server rendering. Features are delivered as vertical slices: each backlog epic ships its API and its screen together.

Browser-based OAuth clients have two main options for token handling. The current IETF guidance for browser apps ("OAuth 2.0 for Browser-Based Applications") prefers a Backend-for-Frontend (BFF) when the data is sensitive, because tokens never reach JavaScript.

## Decision

1. **Single-page app** in `web/`: React, TypeScript, Vite, React Router, TanStack Query.
2. **OIDC Authorization Code + PKCE** against Keycloak (`hip-web` public client, ADR 0002), through `oidc-client-ts` and `react-oidc-context`.
3. **Tokens only in memory.** No `localStorage` or `sessionStorage`. A page reload starts a new sign-in round trip. Access tokens live 5 minutes and are renewed with the refresh token.
4. **Typed API client generated from the contract** (`openapi-typescript` from `contracts/core-api.openapi.yaml`, used through `openapi-fetch`). CI fails if the generated types are stale.
5. **CORS** on `core-api` allows only configured origins, with no credentials (there are no cookies).
6. **A BFF is deferred to Phase 7**, as its own ADR, before any real patient data. It is a security improvement, not a functional need for the MVP.
7. The UI states plainly what is not built yet and never shows fake data. A banner marks the development environment as synthetic-data only.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| BFF now (Spring Cloud Gateway or a small Node/Java BFF with httpOnly session cookies) | Tokens never reach the browser; matches the strictest guidance | One more component to build, deploy and secure, plus CSRF handling, before any feature exists |
| Tokens in `localStorage` | Survive reloads | Any XSS can read them; unacceptable for health data |
| Next.js with server rendering | SSR, built-in server for a future BFF | More moving parts; SSR adds nothing behind a login |
| No frontend in the MVP | Less work | The review journey cannot be validated |

## Consequences

- An XSS flaw could still use the in-memory token while the page is open. Mitigations: React escaping, a strict Content Security Policy when the app is served (Phase 7), no `dangerouslySetInnerHTML`, and the BFF in Phase 7.
- Reloading the page triggers a fast redirect through Keycloak (the SSO session keeps the user signed in).
- The OpenAPI contract now has a real consumer, so contract drift shows up as type errors.
