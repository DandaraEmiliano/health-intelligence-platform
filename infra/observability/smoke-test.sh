#!/usr/bin/env bash
# Smoke test for the observability profile: every component is up, and a synthetic span sent to the
# collector over OTLP/HTTP is queryable in Tempo (proves the whole trace pipeline, including scrubbing).
# Requires: curl, python3. Never sends real data.
set -euo pipefail

retry() {
  local attempts=$1; shift
  for ((i = 1; i <= attempts; i++)); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "FAIL: $*" >&2
  return 1
}

echo "-- Collector health"
retry 30 curl -sf http://localhost:13133/
echo "PASS"

echo "-- Prometheus ready and scraping the collector"
retry 30 curl -sf http://localhost:9091/-/ready
retry 30 sh -c "curl -sf 'http://localhost:9091/api/v1/query?query=up%7Bjob%3D%22otel-collector%22%7D' | grep -q '\"value\":\[[^]]*,\"1\"\]'"
echo "PASS"

echo "-- Grafana healthy with provisioned datasources and dashboard"
retry 30 curl -sf http://localhost:3000/api/health
auth="admin:${GF_SECURITY_ADMIN_PASSWORD:?GF_SECURITY_ADMIN_PASSWORD is required}"
curl -sf -u "$auth" http://localhost:3000/api/datasources/uid/prometheus >/dev/null
curl -sf -u "$auth" http://localhost:3000/api/datasources/uid/tempo >/dev/null
curl -sf -u "$auth" http://localhost:3000/api/dashboards/uid/hip-overview >/dev/null
echo "PASS"

echo "-- Tempo ready"
retry 60 sh -c "curl -sf http://localhost:3200/ready | grep -q ready"
echo "PASS"

echo "-- Span round trip: OTLP/HTTP -> collector -> Tempo, with the authorization header scrubbed"
trace_id=$(python3 -c "import secrets; print(secrets.token_hex(16))")
span_id=$(python3 -c "import secrets; print(secrets.token_hex(8))")
now=$(python3 -c "import time; print(time.time_ns())")
cat > /tmp/hip-span.json <<JSON
{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"smoke-test"}}]},
 "scopeSpans":[{"scope":{"name":"smoke"},"spans":[{"traceId":"$trace_id","spanId":"$span_id","name":"smoke-span",
 "kind":2,"startTimeUnixNano":"$((now - 5000000))","endTimeUnixNano":"$now",
 "attributes":[{"key":"http.request.header.authorization","value":{"stringValue":"Bearer must-be-scrubbed"}},
               {"key":"http.route","value":{"stringValue":"/smoke"}}]}]}]}]}
JSON
curl -sf -X POST http://localhost:4318/v1/traces -H 'Content-Type: application/json' --data @/tmp/hip-span.json >/dev/null
retry 30 curl -sf "http://localhost:3200/api/traces/$trace_id"
body=$(curl -sf "http://localhost:3200/api/traces/$trace_id")
echo "$body" | grep -q smoke-span
echo "$body" | grep -q '/smoke'
if echo "$body" | grep -q 'must-be-scrubbed'; then
  echo "FAIL: authorization header reached Tempo" >&2
  exit 1
fi
echo "PASS"

echo "ALL OBSERVABILITY CHECKS PASSED"
