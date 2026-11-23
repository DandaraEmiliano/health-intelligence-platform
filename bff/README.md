# bff

Backend-for-Frontend ([ADR 0014](../docs/adr/0014-backend-for-frontend.md)). The browser talks only to the web origin, and this service sits behind it:

- **Sign-in:** `/oauth2/authorization/keycloak` runs Authorization Code + PKCE as the confidential Keycloak client `hip-bff`. `?prompt=create` opens the registration page.
- **Session:** tokens stay in a Redis session (Spring Session). The browser gets only `__Host-hip-session`: HttpOnly, Secure, SameSite=Lax, 30-minute idle timeout.
- **API:** `/api/**` is relayed unchanged to core-api with `Authorization: Bearer`, refreshed when needed. Bodies are streamed, so uploads are not buffered. Cookies are never forwarded.
- **CSRF:** unsafe methods need `X-XSRF-TOKEN` echoing the readable `XSRF-TOKEN` cookie.
- **Who is signed in:** `/bff/user` returns e-mail and name only (401 when signed out).
- **Logout:** `POST /logout` ends the session. With `Accept: application/json` it returns `{"redirectUrl"}` (Keycloak's end-session URL), which the SPA navigates to.

## Run

`./scripts/dev.sh` starts it on port 8088. Vite forwards `/api`, `/bff`, `/oauth2`, `/login` and `/logout` there, so the app stays on http://localhost:5173. On its own:

```bash
set -a; . ../.env; set +a
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn spring-boot:run
```

Containers: `docker compose --profile infra --profile apps up -d --build --wait` (nginx in the `web` container forwards to `bff:8080`).

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `KC_HIP_BFF_SECRET` | | Secret of the `hip-bff` client |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/hip` | Issuer, checked on every ID token |
| `OIDC_PUBLIC_BASE` | `http://localhost:8180` | Keycloak as the browser reaches it (authorization, logout) |
| `OIDC_INTERNAL_BASE` | `http://localhost:8180` | Keycloak as this service reaches it (token, keys, user info) |
| `CORE_API_URL` | `http://localhost:8080` | core-api |
| `REDIS_HOST` | `localhost` | Session store |

## Test

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn verify   # Testcontainers Redis and a stub core-api
```
