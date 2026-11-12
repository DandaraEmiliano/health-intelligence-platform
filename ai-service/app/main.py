"""FastAPI application factory. Internal service: reachable only from core-api (ADR 0001, ADR 0002)."""

from fastapi import FastAPI

from app import health


def create_app() -> FastAPI:
    app = FastAPI(
        title="Health Intelligence Platform - ai-service",
        version="0.1.0",
        docs_url=None,  # internal service; contract lives in contracts/
        redoc_url=None,
    )
    app.include_router(health.router)
    return app


app = create_app()
