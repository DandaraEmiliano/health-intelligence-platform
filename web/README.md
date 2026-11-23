# web

Patient web app: React, TypeScript, Vite (ADR 0013). Sign-in and the API go through the BFF on the same origin (ADR 0014): the app never handles a token. Features arrive as vertical slices: each epic ships its API and its screen together.

## Run locally

Prerequisites: Node.js 22+, the `infra` Compose profile running, `core-api` on port 8080 and `bff` on port 8088 (`./scripts/dev.sh` starts all of them).

```bash
cd web
npm ci
npm run dev        # http://localhost:5173; /api, /bff, /oauth2, /login and /logout go to the BFF
```

Sign in with a synthetic user (`patient.a@example.test`, password `KC_SYNTHETIC_USER_PASSWORD` from `.env`) or create an account.

| Variable | Default |
| --- | --- |
| `VITE_API_BASE_URL` | empty (same origin: the dev server or nginx forwards to the BFF) |

The session is an HttpOnly cookie held by the BFF, so a page reload keeps you signed in. Requests send the cookie (`credentials: "same-origin"`), and unsafe methods add `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie. Nothing is written to `localStorage` or `sessionStorage`.

## What is built

| Screen | Route | Backend |
| --- | --- | --- |
| Onboarding: terms and privacy, AI question, profile | shown until done | `/consents`, `/patients` |
| Home: greeting, real counts, AI status, server status | `/` | profile, consents, health records |
| Minha saúde: allergies, medications, conditions (add, edit with `If-Match`, delete with confirmation) | `/minha-saude?aba=…` | `/patients/me/{allergies,medications,conditions}` |
| Meu perfil: data (JSON Merge Patch) and consents (AI on/off) | `/perfil` | `/patients/me`, `/consents` |
| Documentos: drag-and-drop or choose PDFs, upload progress, status per document, open (short-lived link), delete | `/documentos` | `/documents` |
| Exames: reports with what is left to review; review each result against its excerpt (confirm, correct as printed, reject), confirm the rest, finish | `/exames`, `/exames/:id` | `/laboratory-reports`, `/review` |
| Linha do tempo: verified reports and evolution per analyte (never converts units) | `/linha-do-tempo`, `/linha-do-tempo/:id` | `/health-timeline`, `/health-analytics` |
| Fora da referência, Comparar laudos | `/exames/fora-da-referencia`, `/exames/comparar` | `/health-analytics/out-of-range`, `/laboratory-reports/comparison` |
| Assistente: conversations, cited facts linked to the exam, emergency guidance, not-a-diagnosis notices | `/assistente` | `/ai/conversations` |
| Avisos: what happened to uploaded documents | `/avisos` | `/notifications` |
| Baixar meus dados (LGPD export) | in `/perfil` | `/accounts/me/export` |

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
