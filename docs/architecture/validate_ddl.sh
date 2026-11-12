#!/usr/bin/env bash
# Applies ddl-v1-proposal.sql to a fresh database and runs validate_ddl.sql.
#
# Usage: ADMIN_URL=postgresql://postgres@localhost:5432/postgres ./validate_ddl.sh
# Requires: PostgreSQL 16 with the pgvector extension available, a superuser connection.
# The script creates and drops a throwaway database (hip_ddl_check) and the four roles.
set -euo pipefail

ADMIN_URL="${ADMIN_URL:?Set ADMIN_URL to a superuser connection string}"
DB_NAME="hip_ddl_check"
HERE="$(cd "$(dirname "$0")" && pwd)"

cleanup() {
  psql "$ADMIN_URL" -q -c "DROP DATABASE IF EXISTS ${DB_NAME}" >/dev/null 2>&1 || true
  for role in core_app core_owner ai_app ai_owner; do
    psql "$ADMIN_URL" -q -c "DROP ROLE IF EXISTS ${role}" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT
cleanup

psql "$ADMIN_URL" -q -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${DB_NAME}"
DB_URL="${ADMIN_URL%/*}/${DB_NAME}"

psql "$DB_URL" -q -v ON_ERROR_STOP=1 -f "$HERE/ddl-v1-proposal.sql"
echo "DDL applied."
psql "$DB_URL" -q -v ON_ERROR_STOP=1 -f "$HERE/validate_ddl.sql" 2>&1 | sed -n 's/^.*NOTICE:  //p'
