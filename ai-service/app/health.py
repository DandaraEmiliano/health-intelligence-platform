"""Liveness and readiness probes."""

import logging

from fastapi import APIRouter, Depends, Response, status
from pydantic import BaseModel
from sqlalchemy import Engine, text
from sqlalchemy.exc import SQLAlchemyError

from app.db import get_engine

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/health", tags=["health"])

EXPECTED_TABLES = ("document_chunk", "glossary_entry", "call_log")


class HealthStatus(BaseModel):
    status: str
    checks: dict[str, str] = {}


@router.get("/live", response_model=HealthStatus)
def live() -> HealthStatus:
    return HealthStatus(status="UP")


@router.get("/ready", response_model=HealthStatus)
def ready(response: Response, engine: Engine = Depends(get_engine)) -> HealthStatus:  # noqa: B008
    checks: dict[str, str] = {}
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
            checks["database"] = "UP"
            found = connection.execute(
                text(
                    "SELECT count(*) FROM information_schema.tables "
                    "WHERE table_schema = 'ai' AND table_name = ANY(:names)"
                ),
                {"names": list(EXPECTED_TABLES)},
            ).scalar_one()
            checks["schema"] = "UP" if found == len(EXPECTED_TABLES) else "DOWN"
    except SQLAlchemyError:
        logger.warning("readiness: database unavailable", exc_info=False)
        checks["database"] = "DOWN"

    healthy = all(value == "UP" for value in checks.values()) and "schema" in checks
    if not healthy:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return HealthStatus(status="UP" if healthy else "DOWN", checks=checks)
