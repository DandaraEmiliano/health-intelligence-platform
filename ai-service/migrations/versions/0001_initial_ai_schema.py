"""Initial ai schema: document chunks with fail-closed RLS, glossary, call log.

Source of truth for the design: docs/architecture/ddl-v1-proposal.sql (Phase 2, approved).
Runs as the schema owner (ai_owner). Roles, schema, the pgvector extension and default
privileges are created by infra/docker/postgres/init/01-roles-and-schemas.sh.

Revision ID: 0001
Revises:
Create Date: 2026-11-12
"""

import re

from alembic import op

from app.config import get_settings

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

# Dimension is a placeholder until the embedding model is chosen in Phase 5 (ADR 0005, ADR 0008).
EMBEDDING_DIMENSION = 1024


def _app_role() -> str:
    role = get_settings().db_app_user
    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", role):
        raise ValueError(f"invalid database role name: {role!r}")
    return role


def upgrade() -> None:
    op.execute(f"""
        CREATE TABLE ai.document_chunk (
            id              uuid        PRIMARY KEY,
            patient_id      uuid        NOT NULL,
            source_type     text        NOT NULL CHECK (source_type IN ('LAB_REPORT_TEXT', 'LAB_RESULT_SUMMARY')),
            source_id       uuid        NOT NULL,
            chunk_index     integer     NOT NULL CHECK (chunk_index >= 0),
            content         text        NOT NULL,
            embedding       vector({EMBEDDING_DIMENSION}) NOT NULL,
            embedding_model text        NOT NULL,
            created_at      timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT uq_document_chunk UNIQUE (source_type, source_id, chunk_index, embedding_model)
        )
    """)
    op.execute("CREATE INDEX ix_document_chunk_patient ON ai.document_chunk (patient_id, source_type)")
    op.execute("CREATE INDEX ix_document_chunk_embedding ON ai.document_chunk USING hnsw (embedding vector_cosine_ops)")

    # ADR 0011: current_setting() without missing_ok raises when app.patient_id is unset -> fail closed.
    op.execute("ALTER TABLE ai.document_chunk ENABLE ROW LEVEL SECURITY")
    op.execute("ALTER TABLE ai.document_chunk FORCE ROW LEVEL SECURITY")
    op.execute("""
        CREATE POLICY document_chunk_patient_isolation ON ai.document_chunk
            USING (patient_id = current_setting('app.patient_id')::uuid)
            WITH CHECK (patient_id = current_setting('app.patient_id')::uuid)
    """)

    op.execute(f"""
        CREATE TABLE ai.glossary_entry (
            id              uuid        PRIMARY KEY,
            term            text        NOT NULL,
            aliases         text[]      NOT NULL DEFAULT '{{}}',
            content         text        NOT NULL,
            source_url      text        NOT NULL CHECK (source_url ~ '^https://'),
            embedding       vector({EMBEDDING_DIMENSION}) NOT NULL,
            embedding_model text        NOT NULL,
            updated_at      timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT uq_glossary_term UNIQUE (term)
        )
    """)
    op.execute("CREATE INDEX ix_glossary_embedding ON ai.glossary_entry USING hnsw (embedding vector_cosine_ops)")

    op.execute("""
        CREATE TABLE ai.call_log (
            id                uuid        PRIMARY KEY,
            request_id        text        NOT NULL,
            call_type         text        NOT NULL CHECK (call_type IN ('EXTRACTION', 'ANSWER', 'EMBEDDING')),
            provider          text        NOT NULL,
            model             text        NOT NULL,
            prompt_version    text,
            input_tokens      integer     CHECK (input_tokens >= 0),
            output_tokens     integer     CHECK (output_tokens >= 0),
            estimated_cost    numeric(12,6),
            latency_ms        integer     CHECK (latency_ms >= 0),
            guardrail_results jsonb,
            error_code        text,
            created_at        timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX ix_call_log_created ON ai.call_log (created_at DESC)")

    # Default privileges grant full DML; narrow it where the design says so.
    app_role = _app_role()
    op.execute(f"REVOKE INSERT, UPDATE, DELETE ON ai.glossary_entry FROM {app_role}")
    op.execute(f"REVOKE UPDATE, DELETE ON ai.call_log FROM {app_role}")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS ai.call_log")
    op.execute("DROP TABLE IF EXISTS ai.glossary_entry")
    op.execute("DROP TABLE IF EXISTS ai.document_chunk")
