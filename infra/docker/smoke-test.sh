#!/usr/bin/env bash
# Smoke test for `docker compose --profile infra up -d --wait`.
# S3Mock and ElasticMQ images ship without a shell/HTTP client for container health checks,
# so they are verified here through their APIs. Requires: aws CLI v2, curl, docker.
set -euo pipefail

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-local}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-local}"
export AWS_DEFAULT_REGION="${AWS_REGION:-sa-east-1}"

retry() {
  local attempts=$1; shift
  for ((i = 1; i <= attempts; i++)); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "FAIL: $*" >&2
  return 1
}

echo "-- PostgreSQL: roles and schemas"
docker compose exec -T postgres psql -U postgres -d hip -tAc \
  "select string_agg(rolname, ',' order by rolname) from pg_roles where rolname in ('core_owner','core_app','ai_owner','ai_app')" \
  | grep -qx 'ai_app,ai_owner,core_app,core_owner'
docker compose exec -T postgres psql -U postgres -d hip -tAc \
  "select 1 from pg_extension where extname = 'vector'" | grep -qx 1
echo "PASS"

echo "-- Redis"
docker compose exec -T redis redis-cli ping | grep -qx PONG
echo "PASS"

echo "-- Keycloak realm 'hip' (OIDC discovery)"
retry 30 curl -sf http://localhost:8180/realms/hip/.well-known/openid-configuration
curl -sf http://localhost:8180/realms/hip/.well-known/openid-configuration | grep -q '"issuer":"http://localhost:8180/realms/hip"'
echo "PASS"

echo "-- Keycloak access token carries the claims core-api needs (sub, email, aud, roles)"
if [[ -z "${KC_SYNTHETIC_USER_PASSWORD:-}" && -f .env ]]; then
  KC_SYNTHETIC_USER_PASSWORD=$(grep -E '^KC_SYNTHETIC_USER_PASSWORD=' .env | cut -d= -f2-)
fi
token=$(curl -sf -X POST http://localhost:8180/realms/hip/protocol/openid-connect/token \
  -d grant_type=password -d client_id=hip-dev-cli -d username=patient.a@example.test \
  --data-urlencode "password=${KC_SYNTHETIC_USER_PASSWORD:?set KC_SYNTHETIC_USER_PASSWORD or create .env}" \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])')
python3 - "$token" <<'PY'
import base64, json, sys
payload = sys.argv[1].split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
aud = claims.get("aud")
aud = aud if isinstance(aud, list) else [aud]
assert claims.get("sub"), "access token has no sub (is the 'basic' client scope in the realm?)"
assert claims.get("email") == "patient.a@example.test", "access token has no email claim"
assert "core-api" in aud, "access token audience lacks core-api"
assert "PATIENT" in claims.get("roles", []), "access token lacks the PATIENT role"
PY
echo "PASS"

echo "-- S3 (S3Mock): bucket exists, object round trip"
retry 30 aws --endpoint-url http://localhost:9090 s3api head-bucket --bucket hip-documents-local
echo "synthetic" > /tmp/hip-smoke.txt
aws --endpoint-url http://localhost:9090 s3 cp /tmp/hip-smoke.txt s3://hip-documents-local/smoke/hip-smoke.txt >/dev/null
aws --endpoint-url http://localhost:9090 s3 cp s3://hip-documents-local/smoke/hip-smoke.txt - | grep -qx synthetic
echo "PASS"

echo "-- SQS (ElasticMQ): queues and DLQs exist, message round trip"
retry 30 aws --endpoint-url http://localhost:9324 sqs get-queue-url --queue-name document-extraction-requests
for q in document-extraction-requests document-extraction-results ai-indexing-requests; do
  aws --endpoint-url http://localhost:9324 sqs get-queue-url --queue-name "$q" >/dev/null
  aws --endpoint-url http://localhost:9324 sqs get-queue-url --queue-name "$q-dlq" >/dev/null
done
url=$(aws --endpoint-url http://localhost:9324 sqs get-queue-url --queue-name ai-indexing-requests --query QueueUrl --output text)
aws --endpoint-url http://localhost:9324 sqs send-message --queue-url "$url" --message-body smoke >/dev/null
aws --endpoint-url http://localhost:9324 sqs receive-message --queue-url "$url" --wait-time-seconds 2 --query 'Messages[0].Body' --output text | grep -qx smoke
echo "PASS"

echo "ALL SMOKE CHECKS PASSED"
