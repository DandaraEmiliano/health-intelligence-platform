-- V5: built-in sign-in for deployments without an identity provider (hip.auth.mode=local, the
-- Render profile). The credential plays the identity provider's part: its subject is what the
-- account is provisioned with, and deleting it is how a deleted account loses sign-in (T18).
-- Unused when sign-in is Keycloak.
CREATE TABLE core.local_credential (
    subject          text PRIMARY KEY CHECK (subject ~ '^local:[0-9a-f-]{36}$'),
    email_normalized text NOT NULL UNIQUE,
    password_hash    text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
