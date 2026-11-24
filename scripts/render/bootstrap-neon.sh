#!/usr/bin/env bash
# Prepares a hosted PostgreSQL (Neon) for the Render profile: the same roles, schemas and pgvector
# extension as infra/docker/postgres/init (ADR 0005), for a database owner that is not a superuser.
# Tables are not created here: Flyway (core-api) and Alembic (ai-service) do that on first start.
#
# Run once, from your machine, with the owner connection string Neon shows (it needs psql):
#   export NEON_DATABASE_URL='postgresql://neondb_owner:...@ep-....neon.tech/neondb?sslmode=require'
#   export CORE_OWNER_PASSWORD=... CORE_APP_PASSWORD=... AI_OWNER_PASSWORD=... AI_APP_PASSWORD=...
#   ./scripts/render/bootstrap-neon.sh
# Running it again only resets the four passwords to the values given.
set -euo pipefail

: "${NEON_DATABASE_URL:?NEON_DATABASE_URL is required (the owner connection string)}"
for var in CORE_OWNER_PASSWORD CORE_APP_PASSWORD AI_OWNER_PASSWORD AI_APP_PASSWORD; do
  value="${!var:-}"
  if [[ ${#value} -lt 20 ]]; then
    echo "error: $var must have at least 20 characters (try: openssl rand -hex 24)" >&2
    exit 1
  fi
done
command -v psql >/dev/null 2>&1 || { echo "error: psql not found (brew install libpq)" >&2; exit 1; }

psql "$NEON_DATABASE_URL" -v ON_ERROR_STOP=1 -q \
  -v core_owner_pw="$CORE_OWNER_PASSWORD" \
  -v core_app_pw="$CORE_APP_PASSWORD" \
  -v ai_owner_pw="$AI_OWNER_PASSWORD" \
  -v ai_app_pw="$AI_APP_PASSWORD" <<'SQL'
-- Session settings, so the passwords reach the DO block without being printed anywhere.
SET hip.core_owner_pw = :'core_owner_pw';
SET hip.core_app_pw   = :'core_app_pw';
SET hip.ai_owner_pw   = :'ai_owner_pw';
SET hip.ai_app_pw     = :'ai_app_pw';

DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['core_owner', 'core_app', 'ai_owner', 'ai_app'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', r, current_setting('hip.' || r || '_pw'));
    ELSE
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r, current_setting('hip.' || r || '_pw'));
    END IF;
  END LOOP;
END
$$;

-- Not a superuser: membership in the owner roles is what lets this role create their schemas and
-- set their default privileges.
GRANT core_owner, ai_owner TO CURRENT_USER;

-- Neon lets the database owner create pgvector; a self-hosted server may need a superuser for it.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION core_owner;
CREATE SCHEMA IF NOT EXISTS ai   AUTHORIZATION ai_owner;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO core_owner, core_app, ai_owner, ai_app;
REVOKE ALL ON SCHEMA core FROM PUBLIC;
REVOKE ALL ON SCHEMA ai   FROM PUBLIC;
GRANT USAGE ON SCHEMA core TO core_app;
GRANT USAGE ON SCHEMA ai   TO ai_app;

ALTER DEFAULT PRIVILEGES FOR ROLE core_owner IN SCHEMA core
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ai_owner IN SCHEMA ai
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ai_app;
SQL

echo "hip: roles, schemas and pgvector ready in the Neon database"
