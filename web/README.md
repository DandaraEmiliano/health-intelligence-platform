# web

Patient web app: React, TypeScript, Vite (ADR 0013). Features arrive as vertical slices: each epic ships its API and its screen together.

## Run locally

Prerequisites: Node.js 22+, the `infra` Compose profile running, and `core-api` on port 8080.

```bash
cd web
npm ci
npm run dev        # http://localhost:5173 (the Keycloak client only accepts this origin)
```

Sign in with a synthetic user (`patient.a@example.test`, password `KC_SYNTHETIC_USER_PASSWORD` from `.env`) or create an account.

| Variable | Default |
| --- | --- |
| `VITE_OIDC_AUTHORITY` | `http://localhost:8180/realms/hip` |
| `VITE_OIDC_CLIENT_ID` | `hip-web` |
| `VITE_API_BASE_URL` | `http://localhost:8080` |

## Scripts

| Script | Purpose |
| --- | --- |
| `npm run gen:api` | Regenerate `src/api/schema.d.ts` from `contracts/core-api.openapi.yaml` (CI fails if it is stale) |
| `npm run typecheck` | TypeScript |
| `npm run lint` | oxlint (warnings fail) |
| `npm test` | Vitest + Testing Library |
| `npm run build` | Production build |

## Security notes

- Authorization Code + PKCE; tokens are kept in memory only, never in browser storage.
- The API client reads the current token at request time from an in-memory store kept in sync with the OIDC session.
- A Backend-for-Frontend is planned for Phase 7 (ADR 0013).
