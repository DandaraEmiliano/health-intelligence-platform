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

## What is built

| Screen | Route | Backend |
| --- | --- | --- |
| Onboarding: terms and privacy, AI question, profile | shown until done | `/consents`, `/patients` |
| Home: greeting, real counts, AI status, server status | `/` | profile, consents, health records |
| Minha saúde: allergies, medications, conditions (add, edit with `If-Match`, delete with confirmation) | `/minha-saude?aba=…` | `/patients/me/{allergies,medications,conditions}` |
| Meu perfil: data (JSON Merge Patch) and consents (AI on/off) | `/perfil` | `/patients/me`, `/consents` |
| Documentos, Exames, Linha do tempo, Assistente | planned | show what they will do, never fake data |

Visual language (`src/styles.css`): light and colourful. Each area owns one hue (`tone-*` classes), repeated in its menu icon, page header and cards. Coral and amber are kept for what needs attention (severe allergy, moderate allergy), lilac is the action colour. Icons come from `@tabler/icons-react`.

Code layout: `api/` (HTTP helper with ETags and Problem Details, React Query hooks, Portuguese error messages), `health/` (record kinds as data, one generic form and page), `profile/`, `pages/`, `ui/`. Tests run against an in-memory fake of core-api (`src/test/fakeApi.ts`) that follows the contract: ETags, 412 on stale `If-Match`, 404 before onboarding.

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
