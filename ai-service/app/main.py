"""FastAPI application factory. Internal service: reachable only from core-api (ADR 0001, ADR 0002)."""

from fastapi import FastAPI

from app import health
from app.observability import setup_metrics, setup_tracing


def create_app() -> FastAPI:
    app = FastAPI(
        title="Health Intelligence Platform - ai-service",
        version="0.1.0",
        docs_url=None,  # internal service; contract lives in contracts/
        redoc_url=None,
    )
    app.include_router(health.router)
    setup_metrics(app)
    setup_tracing(app)
    return app


app = create_app()
