"""Metrics (Prometheus) and traces (OpenTelemetry) for ai-service (E2-T6).

- Metrics: request count and latency per route template (never per raw path, to keep cardinality
  bounded and keep identifiers out of labels), exposed at /metrics for Prometheus.
- Traces: exported over OTLP/HTTP only when OTEL_EXPORTER_OTLP_ENDPOINT is set. W3C trace context
  from core-api is continued automatically. Request and response bodies are never recorded.
"""

import os
import time

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Histogram, generate_latest
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint

SERVICE_NAME = "ai-service"


class HttpMetrics:
    def __init__(self, registry: CollectorRegistry) -> None:
        labels = ["service", "method", "route", "status"]
        self.requests = Counter("http_server_requests", "HTTP requests handled", labels, registry=registry)
        self.duration = Histogram(
            "http_server_request_duration_seconds",
            "HTTP request duration",
            ["service", "method", "route"],
            registry=registry,
        )


def _route_template(request: Request) -> str:
    """Route template set by the router once the request is matched (e.g. /health/live)."""
    route = request.scope.get("route")
    return getattr(route, "path", None) or "unmatched"


class MetricsMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, metrics: HttpMetrics) -> None:  # type: ignore[no-untyped-def]
        super().__init__(app)
        self.metrics = metrics

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if request.url.path == "/metrics":
            return await call_next(request)
        start = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            elapsed = time.perf_counter() - start
            route = _route_template(request)
            self.metrics.duration.labels(SERVICE_NAME, request.method, route).observe(elapsed)
            self.metrics.requests.labels(SERVICE_NAME, request.method, route, str(status)).inc()


def setup_metrics(app: FastAPI) -> CollectorRegistry:
    registry = CollectorRegistry()
    metrics = HttpMetrics(registry)
    app.add_middleware(MetricsMiddleware, metrics=metrics)

    @app.get("/metrics", include_in_schema=False)
    def prometheus_metrics() -> Response:
        return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)

    return registry


def setup_tracing(app: FastAPI) -> bool:
    """Instrument FastAPI and export spans when an OTLP endpoint is configured. Returns True if enabled."""
    if not os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        return False

    from opentelemetry import trace
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    provider = TracerProvider(resource=Resource.create({"service.name": SERVICE_NAME}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))  # reads OTEL_EXPORTER_OTLP_* env
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app, tracer_provider=provider, excluded_urls="health/live,metrics")
    return True
