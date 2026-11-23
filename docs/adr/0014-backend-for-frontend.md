# ADR 0014: Backend-for-Frontend so tokens never reach the browser

- **Status:** Proposed
- **Date:** 2026-11-23
- **Deciders:** Dandara Emiliano (owner), Claude (architect)
- **Amends:** ADR 0013 (point 6: the deferred BFF)

## Context

ADR 0013 keeps OIDC tokens in the SPA's memory and defers a BFF until before any real patient data. The current IETF guidance for browser apps ("OAuth 2.0 for Browser-Based Applications") recommends a BFF for sensitive data, because a script injected into the page (XSS, a compromised dependency) can read and use tokens held in JavaScript. Health data is sensitive personal data (LGPD art. 11). Phase 7 hardens the platform before any real data.

What already reduces the risk: tokens only in memory, 5-minute access tokens, React escaping, no `dangerouslySetInnerHTML`, and from Phase 7 a strict Content Security Policy served by nginx (`script-src 'self'`).

## Decision

**Add a `bff` service between the browser and `core-api`. The browser holds only an opaque, HttpOnly session cookie; the BFF holds the tokens.**

1. **Same origin.** nginx serves the SPA and forwards `/api/**`, `/oauth2/**`, `/login/**` and `/logout` to the BFF. The browser talks to one origin, so CORS is no longer needed for the web client.
2. **Confidential client.** The BFF signs in with Authorization Code + PKCE as a new confidential Keycloak client `hip-bff`, whose tokens carry the `core-api` audience. The public `hip-web` client is removed once the web client has moved over.
3. **Session.** The session lives in Redis (Spring Session) and stores the access and refresh tokens. The cookie is `__Host-hip-session`: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, no `Domain`. Lax rather than Strict, so the cookie survives the top-level redirect back from an identity provider on another site; unsafe requests are protected by the CSRF token below. Idle timeout 30 minutes, absolute limit 12 hours. Logout ends the session and the Keycloak session (RP-initiated logout).
4. **Token relay.** On each `/api/**` call the BFF adds `Authorization: Bearer` with a fresh access token (refreshing it when needed) and passes the request through unchanged. That covers bodies, multipart uploads, `If-Match`, `Idempotency-Key`, `ETag`, `Location` and `Retry-After`. `core-api` stays a stateless resource server and changes nothing; it already authorizes every request on its own (ADR 0011).
5. **CSRF.** `SameSite=Lax` keeps the cookie off cross-site subrequests and form posts. On top of that, unsafe methods require a CSRF token (double-submit: an `XSRF-TOKEN` cookie readable by the SPA, echoed in `X-XSRF-TOKEN`). Logout is a `fetch` POST with that header; the BFF answers with the identity provider's logout URL and the SPA navigates there.
6. **Implementation.** Spring Boot with Spring Security OAuth2 Client and Spring Session Data Redis, plus a small streaming relay built on the JDK HTTP client. That is the same stack as `core-api`, with no new language. Spring Cloud Gateway was considered, but its current release needs a newer Spring Boot than the one this project uses.
7. **Rollout.** The web client keeps its API types and switches only its transport: no `Authorization` header, `credentials: "same-origin"` and the CSRF header. The in-memory token code goes away, and a reload no longer forces a new sign-in.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Keep tokens in the SPA (ADR 0013) | Nothing to build; already hardened with memory-only tokens and CSP | Any script in the page can use the token while it is open; against current guidance for sensitive data |
| Token handler pattern (OAuth Agent + OAuth Proxy, e.g. Curity) | Standard pattern; SPA stays static | Two more components to operate, encrypted-cookie key management, third-party product |
| BFF inside `core-api` | One service less | Mixes browser sessions into a stateless resource server; breaks the clean resource-server boundary and the future mobile/API clients |
| **Dedicated BFF on the existing Spring stack** | Tokens never in the browser; `core-api` unchanged; one origin, no CORS | One more service and Redis sessions to run; the BFF becomes part of the trusted path |

## Consequences

- The browser stores no token. XSS can still act as the user while the page is open, but it cannot steal a token to use elsewhere. CSP stays on as the first defense.
- One more service in compose and in AWS (an ECS service behind the same ALB). Redis now holds sessions, so its availability affects sign-in.
- `core-api` CORS configuration is kept only for non-browser development tools; the web client no longer needs it.
- Verified by tests: `/api/**` without a session is rejected, an unsafe method without the CSRF token is rejected, the relayed request carries a valid token, and the cookie has the attributes above.
