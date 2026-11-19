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

## Documents

- `POST /api/v1/documents` (multipart `file`) checks the bytes, not the declared type: `%PDF-` header, not password protected, at most 10 MB. The PDF goes to S3 under `documents/<uuid>`; then one transaction writes the document, and when AI consent is granted, the extraction job and an outbox message (ADR 0003). Without consent the document is `UPLOADED` and nothing is sent.
- `OutboxRelay` publishes pending outbox rows to SQS every second (`FOR UPDATE SKIP LOCKED`, so several instances can run). The message body follows `contracts/events/document-extraction-request.v1.schema.json`; a test validates it against the schema.
- `Idempotency-Key` (UUID) is stored in Redis for 24 hours per user: a retry gets the first response back, a different request with the same key gets 422.
- `GET /api/v1/documents/{id}/content` returns a pre-signed link valid for 60 seconds (302, or JSON with `Accept: application/json`), and every call is audited.
- Delete hides the document at once and removes the S3 object after commit.

Local endpoints default to the Compose emulators (`AWS_ENDPOINT_URL_S3`, `AWS_ENDPOINT_URL_SQS`, `REDIS_HOST`); in AWS leave the endpoints empty.

## Tests

```bash
mvn verify
```

| Test | What it proves |
| --- | --- |
| `ModularityTests` | Module boundaries hold (Spring Modulith) and all 12 modules exist |
| `ArchitectureTests` | ADR 0011 rules: clinical repositories expose no unscoped lookups (`CrudRepository`), every repository query takes a typed `PatientId` (or `UserId` for the profile), controllers never touch repositories |
| `SecurityConfigurationTests` | Realm roles map to Spring authorities |
| `CoreApplicationIntegrationTests` | Boots against PostgreSQL + pgvector (Testcontainers): probes up, API requires auth, Flyway ran as owner, app role is DML-only, audit and consent are append-only, no access to schema `ai` |
| `PatientIsolationTests` | Two patients: B can never read, modify or infer A's data. Every new patient endpoint adds its case here |
| `ContractConformanceTests` | Every implemented operation exists in `contracts/core-api.openapi.yaml` with the same path, method and operationId |
| `IdentityApiTests`, `PatientApiTests`, `ConsentApiTests`, `HealthRecordsApiTests`, `DocumentsApiTests` | Behaviour of each endpoint, errors as Problem Details, audit rows |

Integration tests share one set of containers (PostgreSQL, Redis, S3Mock, ElasticMQ, same images as Compose) and one Spring context (`support/IntegrationTest`). Tokens are signed by a test key with the same claims as the local realm (`support/TestTokens`), so no Keycloak is needed.

## Identity and patient scope

- The first authenticated request provisions the local `user_account` from the token (`sub`, `email`, `roles`), just in time; later requests keep email and roles in sync. Concurrent first requests create exactly one account.
- `/api/**` requires the `PATIENT` role. Tokens without `sub` or `email` get 403 `ACCOUNT_CLAIMS_MISSING`.
- The patient is always resolved from the token. A missing profile and another patient's profile are the same 404.
- Profile updates use JSON Merge Patch.
- Profile updates and health record replacements require `If-Match` (428 without it, 412 when stale).
- Allergies, medications and conditions live under `/api/v1/patients/me/...` and need the profile first (404 `PATIENT_NOT_FOUND` otherwise). Each patient may keep up to 200 of each (409 `RECORD_LIMIT_REACHED`). Medications are free text; the platform never interprets or changes dosages.
- Clinical modules get the caller through `CurrentPatient.scope()` (user and patient, from the token) and pass `PatientId` to every repository query.
- Profile, consent and health record changes write an `audit_event` with field names and codes only, never values.
