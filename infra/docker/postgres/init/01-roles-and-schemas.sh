#!/usr/bin/env bash
# Bootstraps roles, schemas and the pgvector extension (ADR 0005).
#
# Runs once, as the superuser, from /docker-entrypoint-initdb.d on an empty data directory.
# The same script is reused by tests and CI against any PostgreSQL 16 + pgvector server.
# Tables are NOT created here: Flyway (core-api) and Alembic (ai-service) own them.
#
# Required environment: CORE_OWNER_PASSWORD, CORE_APP_PASSWORD, AI_OWNER_PASSWORD, AI_APP_PASSWORD.
# Optional: POSTGRES_DB (target database), POSTGRES_USER (superuser), PGHOST/PGPORT for non-container use.
set -euo pipefail

: "${CORE_OWNER_PASSWORD:?CORE_OWNER_PASSWORD is required}"
: "${CORE_APP_PASSWORD:?CORE_APP_PASSWORD is required}"
: "${AI_OWNER_PASSWORD:?AI_OWNER_PASSWORD is required}"
: "${AI_APP_PASSWORD:?AI_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER:-postgres}" \
  --dbname "${POSTGRES_DB:-postgres}" \
  -v core_owner_pw="$CORE_OWNER_PASSWORD" \
  -v core_app_pw="$CORE_APP_PASSWORD" \
  -v ai_owner_pw="$AI_OWNER_PASSWORD" \
  -v ai_app_pw="$AI_APP_PASSWORD" <<'SQL'
CREATE ROLE core_owner LOGIN PASSWORD :'core_owner_pw';
CREATE ROLE core_app   LOGIN PASSWORD :'core_app_pw';
CREATE ROLE ai_owner   LOGIN PASSWORD :'ai_owner_pw';
CREATE ROLE ai_app     LOGIN PASSWORD :'ai_app_pw';

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA core AUTHORIZATION core_owner;
CREATE SCHEMA ai   AUTHORIZATION ai_owner;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO core_owner, core_app, ai_owner, ai_app;
REVOKE ALL ON SCHEMA core FROM PUBLIC;
REVOKE ALL ON SCHEMA ai   FROM PUBLIC;
GRANT USAGE ON SCHEMA core TO core_app;
GRANT USAGE ON SCHEMA ai   TO ai_app;

-- Tables created later by the owner roles are readable/writable by the app roles.
-- Append-only and read-only exceptions are revoked in the migrations themselves.
ALTER DEFAULT PRIVILEGES FOR ROLE core_owner IN SCHEMA core
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ai_owner IN SCHEMA ai
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ai_app;
SQL

echo "hip: roles, schemas and pgvector extension created"
