-- V1: initial core schema (Phase 3).
-- Source of truth for the design: docs/architecture/ddl-v1-proposal.sql (Phase 2, approved).
-- Runs as the schema owner (core_owner). Roles, schema and default privileges are created by
-- infra/docker/postgres/init/01-roles-and-schemas.sh, not by application migrations.
-- identity --------------------------------------------------------------------
CREATE TABLE core.user_account (
    id              uuid        PRIMARY KEY,
    idp_subject     text        NOT NULL,
    email           text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'DELETION_REQUESTED', 'DELETED')),
    deletion_requested_at timestamptz,
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_account_idp_subject UNIQUE (idp_subject),
    CONSTRAINT ck_user_account_deletion CHECK (
        (status = 'ACTIVE' AND deletion_requested_at IS NULL)
        OR (status <> 'ACTIVE' AND deletion_requested_at IS NOT NULL))
);

CREATE TABLE core.user_role (
    user_id uuid NOT NULL REFERENCES core.user_account (id) ON DELETE CASCADE,
    role    text NOT NULL CHECK (role IN ('PATIENT', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

-- consent (append-only) ---------------------------------------------------------
CREATE TABLE core.consent_record (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL REFERENCES core.user_account (id) ON DELETE CASCADE,
    consent_type     text        NOT NULL
                     CHECK (consent_type IN ('TERMS_OF_USE', 'PRIVACY_POLICY', 'AI_PROCESSING')),
    document_version text        NOT NULL,
    granted          boolean     NOT NULL,
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    ip_address       inet,
    user_agent       text
);
CREATE INDEX ix_consent_record_current
    ON core.consent_record (user_id, consent_type, recorded_at DESC);

-- patient -----------------------------------------------------------------------
CREATE TABLE core.patient (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES core.user_account (id) ON DELETE CASCADE,
    full_name      text        NOT NULL CHECK (length(full_name) BETWEEN 1 AND 200),
    birth_date     date        NOT NULL,
    biological_sex text        CHECK (biological_sex IN ('FEMALE', 'MALE', 'INTERSEX', 'UNDISCLOSED')),
    height_cm      numeric(5,1) CHECK (height_cm BETWEEN 30 AND 272),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_patient_user UNIQUE (user_id)
    -- birth_date <= current_date is validated in the application: CHECK cannot use
    -- non-immutable functions reliably.
);

-- healthrecords -----------------------------------------------------------------
CREATE TABLE core.allergy (
    id           uuid        PRIMARY KEY,
    patient_id   uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    substance    text        NOT NULL CHECK (length(substance) BETWEEN 1 AND 200),
    allergy_type text        NOT NULL CHECK (allergy_type IN ('MEDICATION', 'FOOD', 'ENVIRONMENTAL', 'OTHER')),
    severity     text        CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    notes        text        CHECK (length(notes) <= 1000),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_allergy_patient ON core.allergy (patient_id);

CREATE TABLE core.medication (
    id             uuid        PRIMARY KEY,
    patient_id     uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    dosage_text    text        CHECK (length(dosage_text) <= 200),
    frequency_text text        CHECK (length(frequency_text) <= 200),
    started_on     date,
    ended_on       date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_medication_period CHECK (ended_on IS NULL OR started_on IS NULL OR ended_on >= started_on)
);
CREATE INDEX ix_medication_patient ON core.medication (patient_id);

CREATE TABLE core.medical_condition (
    id                uuid        PRIMARY KEY,
    patient_id        uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    description       text        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    status            text        NOT NULL CHECK (status IN ('ACTIVE', 'RESOLVED', 'UNKNOWN')),
    onset_date_approx date,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_medical_condition_patient ON core.medical_condition (patient_id);

-- documents ---------------------------------------------------------------------
CREATE TABLE core.health_document (
    id                uuid        PRIMARY KEY,
    patient_id        uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    document_type     text        NOT NULL DEFAULT 'LAB_REPORT' CHECK (document_type IN ('LAB_REPORT')),
    original_filename text        NOT NULL CHECK (length(original_filename) <= 255),
    content_type      text        NOT NULL CHECK (content_type = 'application/pdf'),
    size_bytes        bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256            char(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key       text        NOT NULL CHECK (storage_key ~ '^documents/[0-9a-f-]{36}$'),
    status            text        NOT NULL DEFAULT 'UPLOADED'
                      CHECK (status IN ('UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED', 'UNSUPPORTED_SCANNED')),
    failure_code      text,
    uploaded_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_health_document_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_health_document_failure CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);
-- Dedupe only among live documents; a deleted document can be uploaded again.
CREATE UNIQUE INDEX uq_health_document_patient_sha256
    ON core.health_document (patient_id, sha256) WHERE deleted_at IS NULL;
CREATE INDEX ix_health_document_patient_uploaded
    ON core.health_document (patient_id, uploaded_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE core.extraction_job (
    id                uuid        PRIMARY KEY,
    document_id       uuid        NOT NULL REFERENCES core.health_document (id) ON DELETE CASCADE,
    patient_id        uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    extractor_version text        NOT NULL CHECK (extractor_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    status            text        NOT NULL DEFAULT 'REQUESTED'
                      CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'UNSUPPORTED_SCANNED')),
    failure_code      text,
    requested_at      timestamptz NOT NULL DEFAULT now(),
    completed_at      timestamptz,
    CONSTRAINT uq_extraction_job_document_version UNIQUE (document_id, extractor_version)
);
CREATE INDEX ix_extraction_job_document ON core.extraction_job (document_id);

-- laboratory --------------------------------------------------------------------
CREATE TABLE core.analyte (
    id              uuid PRIMARY KEY,
    code            text NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,64}$'),
    display_name_pt text NOT NULL,
    category        text NOT NULL,
    default_unit    text,
    CONSTRAINT uq_analyte_code UNIQUE (code)
);

CREATE TABLE core.analyte_alias (
    id               uuid PRIMARY KEY,
    analyte_id       uuid NOT NULL REFERENCES core.analyte (id) ON DELETE CASCADE,
    alias_normalized text NOT NULL CHECK (alias_normalized = lower(alias_normalized)),
    CONSTRAINT uq_analyte_alias UNIQUE (alias_normalized)
);
CREATE INDEX ix_analyte_alias_analyte ON core.analyte_alias (analyte_id);

CREATE TABLE core.laboratory_report (
    id                uuid        PRIMARY KEY,
    patient_id        uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    document_id       uuid        NOT NULL REFERENCES core.health_document (id) ON DELETE CASCADE,
    extraction_job_id uuid        NOT NULL REFERENCES core.extraction_job (id),
    laboratory_name   text,
    collected_at      date,
    collected_at_raw  text,
    reported_at       date,
    status            text        NOT NULL DEFAULT 'PENDING_REVIEW'
                      CHECK (status IN ('PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'SUPERSEDED')),
    verified_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_laboratory_report_job UNIQUE (extraction_job_id),
    CONSTRAINT ck_laboratory_report_verified CHECK ((status = 'VERIFIED') = (verified_at IS NOT NULL))
);
-- At most one active (non-superseded) report per document.
CREATE UNIQUE INDEX uq_laboratory_report_active_per_document
    ON core.laboratory_report (document_id) WHERE status <> 'SUPERSEDED';
CREATE INDEX ix_laboratory_report_patient_collected
    ON core.laboratory_report (patient_id, collected_at DESC NULLS LAST, id DESC);
CREATE INDEX ix_laboratory_report_patient_status
    ON core.laboratory_report (patient_id, status);

CREATE TABLE core.laboratory_result (
    id                 uuid        PRIMARY KEY,
    report_id          uuid        NOT NULL REFERENCES core.laboratory_report (id) ON DELETE CASCADE,
    patient_id         uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    sequence           integer     NOT NULL CHECK (sequence >= 1),
    analyte_id         uuid        REFERENCES core.analyte (id),
    raw_name           text        NOT NULL,
    value_kind         text        NOT NULL CHECK (value_kind IN ('NUMERIC', 'TEXT')),
    value_raw          text        NOT NULL,
    value_numeric      numeric(20,6),
    value_comparator   text        CHECK (value_comparator IN ('<', '<=', '>', '>=')),
    value_text         text,
    unit_raw           text,
    unit_normalized    text,
    ref_range_text     text,
    ref_low            numeric(20,6),
    ref_high           numeric(20,6),
    ref_applies_to     text,
    lab_flag           text        CHECK (lab_flag IN ('HIGH', 'LOW', 'ABNORMAL', 'CRITICAL')),
    computed_flag      text        CHECK (computed_flag IN ('HIGH', 'LOW', 'IN_RANGE')),
    method             text,
    review_status      text        NOT NULL DEFAULT 'PENDING_REVIEW'
                       CHECK (review_status IN ('PENDING_REVIEW', 'VERIFIED', 'CORRECTED', 'REJECTED')),
    reviewed_at        timestamptz,
    extracted_snapshot jsonb       NOT NULL,
    validation_warnings jsonb      NOT NULL DEFAULT '[]'::jsonb,
    source_page        integer     NOT NULL CHECK (source_page >= 1),
    source_excerpt     text        NOT NULL CHECK (length(source_excerpt) <= 500),
    collected_at       date,       -- denormalized from the report for time-series queries
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_laboratory_result_sequence UNIQUE (report_id, sequence),
    CONSTRAINT ck_laboratory_result_value CHECK (
        (value_kind = 'NUMERIC' AND value_numeric IS NOT NULL AND value_text IS NULL)
        OR (value_kind = 'TEXT' AND value_text IS NOT NULL AND value_numeric IS NULL AND value_comparator IS NULL)),
    CONSTRAINT ck_laboratory_result_range CHECK (ref_low IS NULL OR ref_high IS NULL OR ref_low <= ref_high),
    CONSTRAINT ck_laboratory_result_reviewed CHECK ((review_status = 'PENDING_REVIEW') = (reviewed_at IS NULL))
);
CREATE INDEX ix_laboratory_result_report ON core.laboratory_result (report_id);
-- Time series and out-of-range queries read only verified data.
CREATE INDEX ix_laboratory_result_series
    ON core.laboratory_result (patient_id, analyte_id, collected_at DESC, id DESC)
    WHERE review_status IN ('VERIFIED', 'CORRECTED');

-- assistant ---------------------------------------------------------------------
CREATE TABLE core.ai_conversation (
    id         uuid        PRIMARY KEY,
    patient_id uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    title      text        CHECK (length(title) <= 200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_conversation_patient ON core.ai_conversation (patient_id, updated_at DESC, id DESC);

CREATE TABLE core.ai_message (
    id                 uuid        PRIMARY KEY,
    conversation_id    uuid        NOT NULL REFERENCES core.ai_conversation (id) ON DELETE CASCADE,
    patient_id         uuid        NOT NULL REFERENCES core.patient (id) ON DELETE CASCADE,
    role               text        NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content            text        NOT NULL CHECK (length(content) <= 20000),
    structured_payload jsonb,
    prompt_version     text,
    model              text,
    input_tokens       integer     CHECK (input_tokens >= 0),
    output_tokens      integer     CHECK (output_tokens >= 0),
    latency_ms         integer     CHECK (latency_ms >= 0),
    guardrail_outcome  text        CHECK (guardrail_outcome IN ('PASSED', 'REFUSED', 'EMERGENCY_GUIDANCE', 'REGENERATED', 'FAILED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_message_assistant_fields CHECK (
        role = 'USER' OR (structured_payload IS NOT NULL AND prompt_version IS NOT NULL AND guardrail_outcome IS NOT NULL))
);
CREATE INDEX ix_ai_message_conversation ON core.ai_message (conversation_id, created_at, id);

-- notifications -----------------------------------------------------------------
CREATE TABLE core.notification (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES core.user_account (id) ON DELETE CASCADE,
    type       text        NOT NULL
               CHECK (type IN ('DOCUMENT_PROCESSED', 'REVIEW_PENDING', 'EXTRACTION_FAILED', 'DOCUMENT_UNSUPPORTED')),
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- IDs only, never health data
    status     text        NOT NULL DEFAULT 'UNREAD' CHECK (status IN ('UNREAD', 'READ')),
    dedupe_key text        NOT NULL,                        -- e.g. "DOCUMENT_PROCESSED:<documentId>"
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz,
    CONSTRAINT uq_notification_dedupe UNIQUE (user_id, dedupe_key),
    CONSTRAINT ck_notification_read CHECK ((status = 'READ') = (read_at IS NOT NULL))
);
CREATE INDEX ix_notification_user ON core.notification (user_id, status, created_at DESC, id DESC);

-- audit (append-only, no FKs so it survives purges) ------------------------------
CREATE TABLE core.audit_event (
    id             uuid        PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid,
    actor_type     text        NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN')),
    action         text        NOT NULL,
    resource_type  text        NOT NULL,
    resource_id    uuid,
    patient_id     uuid,
    outcome        text        NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    correlation_id text,
    ip_address     inet,
    user_agent     text,
    metadata       jsonb       NOT NULL DEFAULT '{}'::jsonb  -- never clinical content
);
CREATE INDEX ix_audit_event_patient ON core.audit_event (patient_id, occurred_at DESC);
CREATE INDEX ix_audit_event_actor   ON core.audit_event (actor_user_id, occurred_at DESC);

-- outbox (published by a relay to SQS; ADR 0003) ---------------------------------
-- Intra-monolith events use Spring Modulith's Event Publication Registry, whose
-- table is created by Modulith's own schema in Phase 3.
CREATE TABLE core.outbox_event (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    destination    text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_event_unpublished ON core.outbox_event (created_at) WHERE published_at IS NULL;

-- updated_at maintenance -----------------------------------------------------------
CREATE FUNCTION core.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_account', 'patient', 'allergy', 'medication', 'medical_condition',
                             'health_document', 'laboratory_report', 'laboratory_result', 'ai_conversation']
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%1$s_updated_at BEFORE UPDATE ON core.%1$I
                        FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at()', t);
    END LOOP;
END;
$$;

-- privileges for the application role -------------------------------------------
-- DML on new tables is granted to the app role by default privileges (infra/docker/postgres/init).
-- consent_record and audit_event are append-only for the application.
REVOKE UPDATE, DELETE ON core.consent_record, core.audit_event FROM ${appRole};
