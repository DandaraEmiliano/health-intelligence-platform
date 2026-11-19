-- V2: report-level validator warnings (e.g. COLLECTION_DATE_MISSING) from the extractor (ADR 0006).
ALTER TABLE core.laboratory_report
    ADD COLUMN validation_warnings jsonb NOT NULL DEFAULT '[]'::jsonb;
