# ADR 0015: A free demo profile on Render

- **Status:** Accepted
- **Date:** 2026-11-24
- **Deciders:** Dandara Emiliano (owner), Claude (architect)
- **Relates to:** ADR 0002 (identity), ADR 0003 (messaging), ADR 0012 (AWS), ADR 0014 (BFF)

## Context

The platform runs locally with Docker Compose and has an AWS design in Terraform that has never been applied. The owner wants a public demo at no cost. Render's free plan offers sleeping web services with about 512 MB each and a small Key Value store. It has no object storage, no queue service, and no room for Keycloak and a separate BFF next to core-api and ai-service.

## Decision

**Add a `render` deployment profile that swaps infrastructure for configuration, without changing the full design.**

1. **One application service.** core-api serves the SPA (`hip.spa.directory`) with the headers nginx sets, so the browser still talks to one origin.
2. **Built-in sign-in** (`hip.auth.mode=local`).
   - core-api takes the BFF's role from ADR 0014: a server-side session (Spring Session, Redis) behind an HttpOnly `__Host-` cookie, CSRF double submit, and the same `/bff/*` endpoints.
   - Passwords use BCrypt. Failed sign-ins are limited per e-mail.
   - Deleting an account deletes its credential.
   - Bearer tokens are not accepted in this mode.
3. **PDFs in PostgreSQL** (`hip.documents.store=database`). Download links are HMAC-signed and expire like S3 pre-signed URLs. ai-service still reads a PDF by key.
4. **Messages over HTTP** (`hip.messaging.transport=http`).
   - The outbox POSTs to ai-service, and ai-service POSTs results back.
   - Results go through the same validation and ingestion as from SQS.
   - Failed deliveries stay in the outbox and are retried.
5. **One shared secret** (`HIP_INTERNAL_TOKEN`, at least 32 characters, compared in constant time). It replaces the Keycloak service tokens between core-api and ai-service and guards `/internal/**`.
6. **Neon** for PostgreSQL. Its free plan does not expire, unlike Render's, and it has pgvector. The same roles and schemas are prepared by `scripts/render/bootstrap-neon.sh`.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Everything on Render, paid instances | Costs money every month; the owner asked for free |
| One free VM (e.g. Oracle Always Free) with Docker Compose | Runs the full stack unchanged, but the owner preferred Render's simplicity |
| Keycloak on a free instance | Does not fit in 512 MB together with a sleeping JVM start; every sign-in would wait for it |
| Free managed S3 and SQS elsewhere (R2, AWS free tier) | More accounts and keys to manage for a demo; polling SQS exceeds the free request quota |
| Simplify the whole codebase | Loses the documented security architecture and the AWS path |

## Consequences

- **What the demo gives up:**
  - core-api holds browser sessions, which ADR 0014 avoided.
  - A single shared secret is weaker than per-client tokens.
  - An extraction in ai-service memory is lost if the instance stops.
  - Registration answers 409 for an e-mail already in use.

  All of this is acceptable for synthetic data and not for real patients. The profile is labelled as a demo in the UI banner and in the guide.
- **What stays the same:** the defaults are unchanged. Every swap is behind configuration and covered by tests (`RenderProfileTests`, `LocalSignInTests`, ai-service `test_http_transport`). The image is built and scanned in CI like the others.
- **Free plans:** instances sleep after about 15 minutes, so the first request after a pause takes about a minute. Sessions are lost if Key Value restarts.
