"""FastAPI application factory. Internal service: reachable only from core-api (ADR 0001, ADR 0002)."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import health
from app.config import get_settings
from app.observability import setup_metrics, setup_tracing

log = logging.getLogger(__name__)


def _has_anthropic_credentials() -> bool:
    """The SDK reads ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN or an `ant auth login` profile."""
    import os
    from pathlib import Path

    return bool(
        os.environ.get("ANTHROPIC_API_KEY")
        or os.environ.get("ANTHROPIC_AUTH_TOKEN")
        or os.environ.get("ANTHROPIC_PROFILE")
        or (Path.home() / ".config" / "anthropic").is_dir()
    )


def _provider(settings):
    from app.extraction.providers import AnthropicProvider

    if settings.llm_provider != "anthropic":
        raise ValueError(f"unsupported AI_LLM_PROVIDER {settings.llm_provider!r}")
    return AnthropicProvider(model=settings.llm_model, effort=settings.llm_effort)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Starts the extraction worker next to the API (one process locally; separate in AWS later)."""
    settings = get_settings()
    # Without credentials the assistant answers 503 instead of failing every call.
    app.state.assistant_provider = None
    if settings.llm_provider == "anthropic" and _has_anthropic_credentials():
        from app.extraction.providers import AnthropicProvider

        app.state.assistant_provider = AnthropicProvider(model=settings.llm_model, effort=settings.assistant_effort,
                                                         max_tokens=16000, timeout_seconds=60)  # fmt: skip
    worker = None
    app.state.http_worker = None
    if settings.extraction_worker_enabled and settings.llm_provider == "anthropic" and not _has_anthropic_credentials():
        # Without credentials every job would fail for good; leave the documents queued instead.
        log.warning("extraction worker not started: no Anthropic credentials (documents stay queued)")
    elif settings.extraction_worker_enabled and settings.messaging_transport == "http":
        from app.extraction.http_transport import CoreApi, HttpExtractionWorker

        worker = HttpExtractionWorker(
            CoreApi(settings.core_api_url, settings.internal_token.get_secret_value()),
            _provider(settings),
            settings.extraction_max_receives,
        )
        worker.start()
        app.state.http_worker = worker
    elif settings.extraction_worker_enabled:
        from app.extraction.storage import s3_client, s3_fetcher, sqs_client
        from app.extraction.worker import ExtractionWorker

        try:
            worker = ExtractionWorker(
                sqs_client(settings),
                settings.extraction_requests_queue,
                settings.extraction_results_queue,
                s3_fetcher(s3_client(settings)),
                _provider(settings),
                settings.extraction_max_receives,
            )
            worker.start()
        except Exception as e:
            log.warning("extraction worker not started: %s", type(e).__name__)
            worker = None
    yield
    if worker:
        worker.stop()


def create_app() -> FastAPI:
    app = FastAPI(
        lifespan=lifespan,
        title="Health Intelligence Platform - ai-service",
        version="0.1.0",
        docs_url=None,  # internal service; contract lives in contracts/
        redoc_url=None,
    )
    app.include_router(health.router)
    from app.assistant.api import router as assistant_router

    app.include_router(assistant_router)
    if get_settings().messaging_transport == "http":
        from app.extraction.http_transport import router as extraction_router

        app.include_router(extraction_router)
    setup_metrics(app)
    setup_tracing(app)
    return app


app = create_app()
