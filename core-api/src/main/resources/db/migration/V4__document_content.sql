-- V4: PDF content in the database, for deployments without object storage (hip.documents.store=database,
-- the Render profile). Keyed by the same opaque storage key as the S3 object; unused when the store is S3.
CREATE TABLE core.document_content (
    storage_key text PRIMARY KEY CHECK (storage_key ~ '^documents/[0-9a-f-]{36}$'),
    content     bytea NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
