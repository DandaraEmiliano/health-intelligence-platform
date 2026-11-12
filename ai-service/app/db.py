"""Database access. Every query on patient data runs inside `patient_scope` (ADR 0011)."""

from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from uuid import UUID

from sqlalchemy import Connection, Engine, create_engine, text

from app.config import get_settings


@lru_cache
def get_engine() -> Engine:
    return create_engine(get_settings().app_database_url(), pool_pre_ping=True, pool_size=5)


@contextmanager
def patient_scope(engine: Engine, patient_id: UUID) -> Iterator[Connection]:
    """Open a transaction whose row-level security is bound to one patient.

    `SET LOCAL` keeps the setting inside this transaction only, so a pooled connection never
    carries a previous patient's scope. Without it, RLS on `ai.document_chunk` fails closed.
    """
    if not isinstance(patient_id, UUID):
        raise TypeError("patient_id must be a UUID")
    with engine.begin() as connection:
        connection.execute(text("SELECT set_config('app.patient_id', :pid, true)"), {"pid": str(patient_id)})
        yield connection
