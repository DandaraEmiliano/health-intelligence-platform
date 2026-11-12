-- Assertions for ddl-v1-proposal.sql. Run on a FRESH database after applying the DDL,
-- as a superuser, with ON_ERROR_STOP. Every block raises an exception if an
-- expectation fails. Uses synthetic UUIDs only.
\set ON_ERROR_STOP on

-- Fixtures ----------------------------------------------------------------------
INSERT INTO core.user_account (id, idp_subject, email) VALUES
  ('00000000-0000-7000-8000-00000000000a', 'kc-sub-a', 'patient.a@example.test'),
  ('00000000-0000-7000-8000-00000000000b', 'kc-sub-b', 'patient.b@example.test');
INSERT INTO core.patient (id, user_id, full_name, birth_date) VALUES
  ('00000000-0000-7000-8000-0000000000a1', '00000000-0000-7000-8000-00000000000a', 'Paciente Sintético A', '1990-01-01'),
  ('00000000-0000-7000-8000-0000000000b1', '00000000-0000-7000-8000-00000000000b', 'Paciente Sintético B', '1985-06-15');
INSERT INTO core.health_document (id, patient_id, original_filename, content_type, size_bytes, sha256, storage_key) VALUES
  ('00000000-0000-7000-8000-0000000000d1', '00000000-0000-7000-8000-0000000000a1', 'exame.pdf', 'application/pdf', 1000,
   repeat('a', 64), 'documents/00000000-0000-7000-8000-0000000000d1');
INSERT INTO core.extraction_job (id, document_id, patient_id, extractor_version) VALUES
  ('00000000-0000-7000-8000-0000000000e1', '00000000-0000-7000-8000-0000000000d1', '00000000-0000-7000-8000-0000000000a1', '1.0.0');
INSERT INTO core.laboratory_report (id, patient_id, document_id, extraction_job_id, collected_at) VALUES
  ('00000000-0000-7000-8000-0000000000f1', '00000000-0000-7000-8000-0000000000a1', '00000000-0000-7000-8000-0000000000d1',
   '00000000-0000-7000-8000-0000000000e1', '2026-03-10');

-- Helper: expect a statement to fail with a given SQLSTATE --------------------------
CREATE FUNCTION pg_temp.expect_error(stmt text, expected_state text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    RAISE EXCEPTION 'FAIL: % (statement succeeded, expected SQLSTATE %)', label, expected_state;
EXCEPTION
    WHEN OTHERS THEN
        IF SQLSTATE = 'P0001' AND SQLERRM LIKE 'FAIL:%' THEN RAISE; END IF;
        IF SQLSTATE <> expected_state THEN
            RAISE EXCEPTION 'FAIL: % (got SQLSTATE % "%", expected %)', label, SQLSTATE, SQLERRM, expected_state;
        END IF;
        RAISE NOTICE 'PASS: %', label;
END;
$$;

-- 1. Laboratory result invariants ----------------------------------------------------
SELECT pg_temp.expect_error($s$
  INSERT INTO core.laboratory_result (id, report_id, patient_id, sequence, raw_name, value_kind, value_raw,
    value_numeric, value_text, extracted_snapshot, source_page, source_excerpt)
  VALUES (gen_random_uuid(), '00000000-0000-7000-8000-0000000000f1', '00000000-0000-7000-8000-0000000000a1', 1,
    'HBsAg', 'TEXT', 'Não reagente', 1, 'Não reagente', '{}', 1, 'HBsAg: Não reagente')
$s$, '23514', 'TEXT result cannot carry a numeric value');

SELECT pg_temp.expect_error($s$
  INSERT INTO core.laboratory_result (id, report_id, patient_id, sequence, raw_name, value_kind, value_raw,
    value_numeric, ref_low, ref_high, extracted_snapshot, source_page, source_excerpt)
  VALUES (gen_random_uuid(), '00000000-0000-7000-8000-0000000000f1', '00000000-0000-7000-8000-0000000000a1', 1,
    'Ferritina', 'NUMERIC', '12,4', 12.4, 150, 15, '{}', 2, 'FERRITINA 12,4')
$s$, '23514', 'reference range low must not exceed high');

INSERT INTO core.laboratory_result (id, report_id, patient_id, sequence, raw_name, value_kind, value_raw,
  value_numeric, unit_raw, ref_range_text, ref_low, ref_high, lab_flag, extracted_snapshot, source_page, source_excerpt, collected_at)
VALUES ('00000000-0000-7000-8000-000000000101', '00000000-0000-7000-8000-0000000000f1', '00000000-0000-7000-8000-0000000000a1', 1,
  'Ferritina', 'NUMERIC', '12,4', 12.4, 'ng/mL', 'Mulheres: 15 a 150 ng/mL', 15, 150, 'LOW', '{"rawValue":"12,4"}', 2,
  'FERRITINA 12,4 ng/mL L', '2026-03-10');
DO $$ BEGIN
  IF (SELECT value_numeric FROM core.laboratory_result WHERE id = '00000000-0000-7000-8000-000000000101') <> 12.4 THEN
    RAISE EXCEPTION 'FAIL: numeric value must be stored exactly';
  END IF;
  RAISE NOTICE 'PASS: numeric value stored exactly (no float rounding)';
END $$;

SELECT pg_temp.expect_error($s$
  UPDATE core.laboratory_result SET review_status = 'VERIFIED' WHERE id = '00000000-0000-7000-8000-000000000101'
$s$, '23514', 'reviewed result must have reviewed_at');

-- 2. Document dedupe and report uniqueness -----------------------------------------
SELECT pg_temp.expect_error($s$
  INSERT INTO core.health_document (id, patient_id, original_filename, content_type, size_bytes, sha256, storage_key)
  VALUES ('00000000-0000-7000-8000-0000000000d2', '00000000-0000-7000-8000-0000000000a1', 'copia.pdf', 'application/pdf', 1000,
    repeat('a', 64), 'documents/00000000-0000-7000-8000-0000000000d2')
$s$, '23505', 'same PDF cannot be uploaded twice by the same patient while live');

INSERT INTO core.health_document (id, patient_id, original_filename, content_type, size_bytes, sha256, storage_key)
VALUES ('00000000-0000-7000-8000-0000000000d3', '00000000-0000-7000-8000-0000000000b1', 'exame.pdf', 'application/pdf', 1000,
  repeat('a', 64), 'documents/00000000-0000-7000-8000-0000000000d3');
DO $$ BEGIN RAISE NOTICE 'PASS: another patient may upload an identical PDF'; END $$;

SELECT pg_temp.expect_error($s$
  INSERT INTO core.health_document (id, patient_id, original_filename, content_type, size_bytes, sha256, storage_key)
  VALUES (gen_random_uuid(), '00000000-0000-7000-8000-0000000000a1', 'x.pdf', 'application/pdf', 1000,
    repeat('b', 64), 'documents/joao-da-silva.pdf')
$s$, '23514', 'storage key must be opaque (no personal data)');

SELECT pg_temp.expect_error($s$
  INSERT INTO core.extraction_job (id, document_id, patient_id, extractor_version)
  VALUES (gen_random_uuid(), '00000000-0000-7000-8000-0000000000d1', '00000000-0000-7000-8000-0000000000a1', '1.0.0')
$s$, '23505', 'extraction job is idempotent per document and extractor version');

-- 3. Privilege separation between services ------------------------------------------
SET ROLE core_app;
SELECT pg_temp.expect_error('SELECT count(*) FROM ai.document_chunk', '42501', 'core_app cannot read schema ai');
SELECT pg_temp.expect_error($s$
  UPDATE core.audit_event SET outcome = 'SUCCESS'
$s$, '42501', 'core_app cannot update audit events (append-only)');
SELECT pg_temp.expect_error($s$
  DELETE FROM core.consent_record
$s$, '42501', 'core_app cannot delete consent records (append-only)');
RESET ROLE;

SET ROLE ai_app;
SELECT pg_temp.expect_error('SELECT count(*) FROM core.patient', '42501', 'ai_app cannot read schema core');
RESET ROLE;

-- 4. Row-level security on ai.document_chunk (ADR 0011) -------------------------------
-- Fixtures inserted as superuser with the session variable set (FORCE RLS applies to owner, not superuser).
INSERT INTO ai.document_chunk (id, patient_id, source_type, source_id, chunk_index, content, embedding, embedding_model)
SELECT gen_random_uuid(), p.pid, 'LAB_REPORT_TEXT', gen_random_uuid(), 0, 'synthetic chunk for ' || p.label,
       (SELECT array_agg(random())::vector(1024) FROM generate_series(1, 1024)), 'fake-embedding'
FROM (VALUES ('00000000-0000-7000-8000-0000000000a1'::uuid, 'A'), ('00000000-0000-7000-8000-0000000000b1'::uuid, 'B')) AS p(pid, label);

BEGIN;
SET LOCAL ROLE ai_app;
SELECT pg_temp.expect_error('SELECT count(*) FROM ai.document_chunk', '42704',
  'RLS fails closed when app.patient_id is not set');
ROLLBACK;

BEGIN;
SET LOCAL ROLE ai_app;
SET LOCAL app.patient_id = '00000000-0000-7000-8000-0000000000a1';
DO $$ BEGIN
  IF (SELECT count(*) FROM ai.document_chunk) <> 1
     OR EXISTS (SELECT 1 FROM ai.document_chunk WHERE patient_id <> '00000000-0000-7000-8000-0000000000a1') THEN
    RAISE EXCEPTION 'FAIL: patient A must see only their own chunk';
  END IF;
  RAISE NOTICE 'PASS: RLS returns only the current patient''s chunks';
END $$;
DO $$ BEGIN
  -- Even an explicit filter for patient B returns nothing.
  IF (SELECT count(*) FROM ai.document_chunk WHERE patient_id = '00000000-0000-7000-8000-0000000000b1') <> 0 THEN
    RAISE EXCEPTION 'FAIL: explicit query for patient B leaked rows';
  END IF;
  RAISE NOTICE 'PASS: explicit query for another patient returns nothing';
END $$;
SELECT pg_temp.expect_error($s$
  INSERT INTO ai.document_chunk (id, patient_id, source_type, source_id, chunk_index, content, embedding, embedding_model)
  SELECT gen_random_uuid(), '00000000-0000-7000-8000-0000000000b1', 'LAB_REPORT_TEXT', gen_random_uuid(), 0, 'x',
         (SELECT array_agg(0.1)::vector(1024) FROM generate_series(1, 1024)), 'fake-embedding'
$s$, '42501', 'RLS rejects writing a chunk for another patient');
ROLLBACK;

-- 5. Index usage for the hot queries --------------------------------------------------
SET enable_seqscan = off;
DO $$
DECLARE plan text := ''; line text;
BEGIN
  FOR line IN EXECUTE $q$EXPLAIN SELECT id, value_numeric, collected_at FROM core.laboratory_result
            WHERE patient_id = '00000000-0000-7000-8000-0000000000a1'
              AND analyte_id = '00000000-0000-7000-8000-000000000999'
              AND review_status IN ('VERIFIED', 'CORRECTED')
            ORDER BY collected_at DESC, id DESC LIMIT 50$q$
  LOOP
    plan := plan || line || E'\n';
  END LOOP;
  IF plan NOT LIKE '%ix_laboratory_result_series%' THEN
    RAISE EXCEPTION 'FAIL: time-series query does not use ix_laboratory_result_series: %', plan;
  END IF;
  RAISE NOTICE 'PASS: time-series query can use ix_laboratory_result_series';
END $$;
RESET enable_seqscan;

DO $$ BEGIN RAISE NOTICE 'ALL DDL ASSERTIONS PASSED'; END $$;
