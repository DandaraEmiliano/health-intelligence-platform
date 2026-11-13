import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import ClassVar

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.observability import setup_tracing


def test_metrics_endpoint_exposes_request_metrics_by_route_template() -> None:
    client = TestClient(create_app())
    client.get("/health/live")
    client.get("/does-not-exist/00000000-0000-7000-8000-0000000000a1")

    body = client.get("/metrics").text

    assert 'http_server_requests_total{method="GET",route="/health/live",service="ai-service",status="200"} 1.0' in body
    # Unknown paths are grouped: identifiers in raw paths never become label values.
    assert 'route="unmatched"' in body
    assert "0000000000a1" not in body
    assert "http_server_request_duration_seconds_bucket" in body


def test_metrics_endpoint_does_not_count_itself() -> None:
    client = TestClient(create_app())
    client.get("/metrics")

    assert 'route="/metrics"' not in client.get("/metrics").text


def test_tracing_is_disabled_without_an_otlp_endpoint(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    assert setup_tracing(create_app()) is False


class _OtlpSink(BaseHTTPRequestHandler):
    received: ClassVar[list[bytes]] = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        _OtlpSink.received.append(self.rfile.read(length))
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args: object) -> None:
        pass


def test_spans_are_exported_over_otlp_and_continue_the_caller_trace(monkeypatch: pytest.MonkeyPatch) -> None:
    server = HTTPServer(("127.0.0.1", 0), _OtlpSink)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")

    from opentelemetry import trace

    app = create_app()  # setup_tracing runs inside create_app and sees the endpoint
    caller_trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
    TestClient(app).get("/health/ready", headers={"traceparent": f"00-{caller_trace_id}-00f067aa0ba902b7-01"})
    trace.get_tracer_provider().force_flush()  # type: ignore[attr-defined]
    server.shutdown()

    payload = b"".join(_OtlpSink.received)
    assert payload, "no OTLP export received"
    assert bytes.fromhex(caller_trace_id) in payload  # same trace id as core-api's traceparent
    assert b"ai-service" in payload
    assert json.dumps("Bearer").encode() not in payload
