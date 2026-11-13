# core-api

Modular monolith (Java 25, Spring Boot 4.1, Spring Modulith 2.1). Source of truth for patient data and the only public entry point (ADR 0001, 0004).

## Run locally

Prerequisites: JDK 25, Maven 3.9+, Docker.

```bash
# from the repository root
cp .env.example .env
docker compose --profile infra up -d --wait

cd core-api
set -a && . ../.env && set +a
mvn spring-boot:run
```

- Readiness: http://localhost:8080/actuator/health/readiness
- Swagger UI (local only): http://localhost:8080/swagger-ui.html
- Metrics: http://localhost:8080/actuator/prometheus. Traces: set `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces` (see `docs/architecture/observability.md`).
- Every `/api/**` request needs a Keycloak access token (realm `hip`, audience `core-api`).

Get a token for a synthetic user (local realm only):

```bash
curl -s -X POST http://localhost:8180/realms/hip/protocol/openid-connect/token \
  -d grant_type=password -d client_id=hip-dev-cli \
  -d username=patient.a@example.test -d "password=$KC_SYNTHETIC_USER_PASSWORD" | jq -r .access_token
```

## Database

- Flyway migrations in `src/main/resources/db/migration` run as `core_owner`. The application connects as `core_app` (DML only).
- Roles, schemas and default privileges come from `infra/docker/postgres/init/01-roles-and-schemas.sh`.

## Tests

```bash
mvn verify
```

| Test | What it proves |
| --- | --- |
| `ModularityTests` | Module boundaries hold (Spring Modulith) and all 12 modules exist |
| `SecurityConfigurationTests` | Realm roles map to Spring authorities |
| `CoreApplicationIntegrationTests` | Boots against PostgreSQL + pgvector (Testcontainers): probes up, API requires auth, Flyway ran as owner, app role is DML-only, audit and consent are append-only, no access to schema `ai` |
