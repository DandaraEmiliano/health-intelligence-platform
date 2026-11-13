# Observability (E2-T6)

Traces show one request end to end (browser → core-api → ai-service, and later the asynchronous extraction via SQS). Metrics show rate, errors and latency per service. Both are designed to carry **no health data**.

## Signals

| Signal | core-api | ai-service | Backend |
| --- | --- | --- | --- |
| Metrics | Micrometer → `/actuator/prometheus` (tag `service=core-api`, latency histograms) | `prometheus_client` → `/metrics` (request count and latency per route template) | Prometheus scrapes both |
| Traces | `spring-boot-starter-opentelemetry` → OTLP/HTTP | OpenTelemetry SDK + FastAPI instrumentation → OTLP/HTTP | Collector → Tempo |
| Logs | Structured JSON (ECS) to stdout with trace ids | stdout | Not aggregated yet (Phase 7) |

Trace context crosses services with the W3C `traceparent` header (HTTP) and, from Phase 5, SQS message attributes (ADR 0003).

## Local stack (`observability` profile)

```bash
docker compose --profile infra --profile observability up -d --wait
```

| Component | URL |
| --- | --- |
| Grafana (user `admin`, password `GF_SECURITY_ADMIN_PASSWORD`) | http://localhost:3000 — dashboard "HIP - Service overview" |
| Prometheus | http://localhost:9091 |
| Tempo API | http://localhost:3200 |
| OTLP endpoint (collector) | http://localhost:4318 (HTTP), localhost:4317 (gRPC) |

Enable trace export when running the services:

```bash
# core-api
export MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces
# ai-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Without these variables nothing is exported, and the services run normally.

## Privacy rules for telemetry (threat model T10)

- No request or response bodies, document text, results or prompts in spans, logs or metrics.
- Metric labels use route templates (`/api/v1/documents/{documentId}`), never raw paths with identifiers.
- The collector deletes attributes that could carry credentials or personal data (`authorization` and `cookie` headers, URL query strings, full URLs, database statements, `enduser.id`), as defence in depth. The smoke test proves the `authorization` header never reaches Tempo.
- Sampling is 100% locally (`TRACING_SAMPLING_PROBABILITY`); production sampling is decided in Phase 7.

## Verification

- `infra/observability/smoke-test.sh` (CI `compose` workflow): every component up, provisioned datasources and dashboard present, a synthetic span goes collector → Tempo with the authorization header scrubbed.
- core-api integration tests: Prometheus output carries the service tag and latency histograms; a tracer is active.
- ai-service tests: metrics by route template (identifiers never become labels); spans exported over OTLP and continuing the caller's trace id.
