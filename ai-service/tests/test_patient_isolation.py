"""Patient isolation in the ai schema (ADR 0011). Runs against a real PostgreSQL + pgvector."""

import uuid
from collections.abc import Iterator

import pytest
from sqlalchemy import Connection, Engine, create_engine, text
from sqlalchemy.exc import DataError, DBAPIError, ProgrammingError
from sqlalchemy.pool import NullPool

from app.db import patient_scope

pytestmark = pytest.mark.db

PATIENT_A = uuid.UUID("00000000-0000-7000-8000-0000000000a1")
PATIENT_B = uuid.UUID("00000000-0000-7000-8000-0000000000b1")
DIMENSION = 1024


def _vector(value: float) -> str:
    return "[" + ",".join([str(value)] * DIMENSION) + "]"


def _insert_chunk(connection: Connection, patient_id: uuid.UUID, content: str) -> None:
    connection.execute(
        text(
            "INSERT INTO ai.document_chunk "
            "(id, patient_id, source_type, source_id, chunk_index, content, embedding, embedding_model) "
            "VALUES (:id, :pid, 'LAB_REPORT_TEXT', :sid, 0, :content, CAST(:emb AS vector), 'fake-embedding')"
        ),
        {"id": uuid.uuid4(), "pid": patient_id, "sid": uuid.uuid4(), "content": content, "emb": _vector(0.1)},
    )


@pytest.fixture
def two_patients(app_engine: Engine) -> Iterator[None]:
    with patient_scope(app_engine, PATIENT_A) as connection:
        _insert_chunk(connection, PATIENT_A, "synthetic chunk A")
    with patient_scope(app_engine, PATIENT_B) as connection:
        _insert_chunk(connection, PATIENT_B, "synthetic chunk B")
    yield
    for patient in (PATIENT_A, PATIENT_B):
        with patient_scope(app_engine, patient) as connection:
            connection.execute(text("DELETE FROM ai.document_chunk"))


def test_query_without_patient_scope_fails_closed_on_fresh_session(settings, migrated: None) -> None:
    # A brand-new session has never defined app.patient_id: current_setting() raises.
    engine = create_engine(settings.app_database_url(), poolclass=NullPool)
    try:
        with engine.connect() as connection, pytest.raises(ProgrammingError, match=r"app\.patient_id"):
            connection.execute(text("SELECT count(*) FROM ai.document_chunk")).scalar_one()
    finally:
        engine.dispose()


def test_query_without_patient_scope_fails_closed_on_reused_session(app_engine: Engine, two_patients: None) -> None:
    # After a scoped transaction, PostgreSQL keeps the placeholder defined as '' for the session.
    # The policy's ::uuid cast then raises instead of matching: still fail-closed, never empty-but-OK.
    with app_engine.connect() as connection:
        connection.execute(text("SELECT set_config('app.patient_id', :pid, true)"), {"pid": str(PATIENT_A)})
        connection.commit()
        with pytest.raises(DataError, match="invalid input syntax for type uuid"):
            connection.execute(text("SELECT count(*) FROM ai.document_chunk")).scalar_one()


def test_patient_sees_only_own_chunks(app_engine: Engine, two_patients: None) -> None:
    with patient_scope(app_engine, PATIENT_A) as connection:
        rows = connection.execute(text("SELECT patient_id, content FROM ai.document_chunk")).all()

    assert [(row.patient_id, row.content) for row in rows] == [(PATIENT_A, "synthetic chunk A")]


def test_explicit_filter_for_other_patient_returns_nothing(app_engine: Engine, two_patients: None) -> None:
    with patient_scope(app_engine, PATIENT_A) as connection:
        count = connection.execute(
            text("SELECT count(*) FROM ai.document_chunk WHERE patient_id = :other"), {"other": PATIENT_B}
        ).scalar_one()

    assert count == 0


def test_vector_search_never_crosses_patients(app_engine: Engine, two_patients: None) -> None:
    with patient_scope(app_engine, PATIENT_B) as connection:
        rows = connection.execute(
            text("SELECT content FROM ai.document_chunk ORDER BY embedding <=> CAST(:q AS vector) LIMIT 10"),
            {"q": _vector(0.1)},
        ).all()

    assert [row.content for row in rows] == ["synthetic chunk B"]


def test_cannot_write_chunk_for_another_patient(app_engine: Engine) -> None:
    with pytest.raises(DBAPIError, match="row-level security"), patient_scope(app_engine, PATIENT_A) as conn:
        _insert_chunk(conn, PATIENT_B, "should be rejected")


def test_scope_does_not_leak_to_the_next_transaction(app_engine: Engine, two_patients: None) -> None:
    with patient_scope(app_engine, PATIENT_A) as connection:
        connection.execute(text("SELECT 1"))
    with app_engine.connect() as connection:
        setting = connection.execute(text("SELECT current_setting('app.patient_id', true)")).scalar_one()

    assert setting in (None, "")


def test_patient_scope_rejects_non_uuid(app_engine: Engine) -> None:
    with pytest.raises(TypeError), patient_scope(app_engine, "' OR 1=1 --"):  # type: ignore[arg-type]
        pass


def test_app_role_cannot_modify_glossary_or_call_log_history(app_engine: Engine) -> None:
    with app_engine.connect() as connection, pytest.raises(ProgrammingError, match="permission denied"):
        connection.execute(text("DELETE FROM ai.glossary_entry"))
    with app_engine.connect() as connection, pytest.raises(ProgrammingError, match="permission denied"):
        connection.execute(text("UPDATE ai.call_log SET error_code = 'X'"))


def test_app_role_cannot_read_core_schema(app_engine: Engine) -> None:
    with app_engine.connect() as connection, pytest.raises(ProgrammingError, match="permission denied"):
        connection.execute(text("SELECT 1 FROM core.patient"))
