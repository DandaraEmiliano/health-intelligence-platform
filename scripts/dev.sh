#!/usr/bin/env bash
# Local development launcher: infrastructure (Docker Compose) + core-api + ai-service + web.
#
#   ./scripts/dev.sh            start everything (same as `up`)
#   ./scripts/dev.sh status     show what is running
#   ./scripts/dev.sh logs core-api|ai-service|web
#   ./scripts/dev.sh stop       stop the services and the containers (data is kept)
#
# Works on macOS and Linux. Uses Java 25 for core-api only (JAVA_HOME is set for that process),
# so other projects on the machine keep their own Java version.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$ROOT/logs"
SERVICES=(core-api ai-service web)
COMPOSE=(docker compose --profile infra --profile observability)

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok() { printf '  \033[32m✔\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✘\033[0m %s\n' "$*" >&2; exit 1; }

# --- prerequisites ---------------------------------------------------------------------------

find_java25() {
  local candidate
  if [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25'; then
    echo "$JAVA_HOME"; return 0
  fi
  if [[ -x /usr/libexec/java_home ]] && candidate=$(/usr/libexec/java_home -v 25 2>/dev/null); then
    echo "$candidate"; return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    candidate="$(brew --prefix openjdk@25 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [[ -x "$candidate/bin/java" ]]; then echo "$candidate"; return 0; fi
  fi
  for candidate in /usr/lib/jvm/*25*; do
    if [[ -x "$candidate/bin/java" ]]; then echo "$candidate"; return 0; fi
  done
  return 1
}

check_prerequisites() {
  bold "Checking prerequisites"
  if ! docker info >/dev/null 2>&1; then
    if [[ "$(uname)" == "Darwin" ]]; then
      warn "Docker is not running; opening Docker Desktop..."
      open -a Docker || fail "Docker Desktop is not installed (brew install --cask docker)"
      for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
    fi
    docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker and run this again."
  fi
  ok "Docker $(docker info --format '{{.ServerVersion}}')"

  JAVA25_HOME="$(find_java25)" || fail "Java 25 not found. Install it with: brew install openjdk@25"
  ok "Java 25 at $JAVA25_HOME (used only for core-api)"

  command -v mvn >/dev/null 2>&1 || fail "Maven not found (brew install maven)"
  ok "$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3)"

  command -v node >/dev/null 2>&1 || fail "Node.js not found (brew install node@22)"
  local node_major
  node_major=$(node -p 'process.versions.node.split(".")[0]')
  ((node_major >= 22)) || fail "Node.js 22+ required, found $(node -v)"
  ok "Node.js $(node -v)"

  command -v uv >/dev/null 2>&1 || fail "uv not found (brew install uv)"
  ok "$(uv --version)"

  for port in 8080 8081 5173; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && ! is_ours_on_port "$port"; then
      fail "Port $port is already in use by another process (lsof -nP -iTCP:$port -sTCP:LISTEN)"
    fi
  done
}

is_ours_on_port() {
  local port=$1 service
  case $port in 8080) service=core-api ;; 8081) service=ai-service ;; 5173) service=web ;; esac
  is_running "$service"
}

# --- process management ------------------------------------------------------------------------

pid_file() { echo "$RUN_DIR/$1.pid"; }

is_running() {
  local file
  file=$(pid_file "$1")
  [[ -f "$file" ]] && kill -0 "$(cat "$file")" 2>/dev/null
}

start_service() {
  local name=$1; shift
  if is_running "$name"; then ok "$name already running"; return; fi
  mkdir -p "$RUN_DIR" "$LOG_DIR"
  # A new session/process group lets `stop` terminate the whole tree (mvn forks the JVM).
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup bash -c "$*" >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
  else
    set -m
    nohup bash -c "$*" >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
    set +m
  fi
  echo $! >"$(pid_file "$name")"
  ok "$name starting (logs: logs/$name.log)"
}

stop_service() {
  local name=$1 file pid
  file=$(pid_file "$name")
  [[ -f "$file" ]] || return 0
  pid=$(cat "$file")
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$pid" 2>/dev/null || true
    ok "$name stopped"
  fi
  rm -f "$file"
}

wait_for() {
  local name=$1 url=$2 attempts=${3:-120}
  for _ in $(seq 1 "$attempts"); do
    if curl -sf "$url" >/dev/null 2>&1; then ok "$name ready"; return 0; fi
    if ! is_running "$name"; then
      warn "$name exited; last log lines:"
      tail -n 25 "$LOG_DIR/$name.log" >&2 || true
      exit 1
    fi
    sleep 2
  done
  warn "$name did not become ready in time; see logs/$name.log"
  return 1
}

# --- commands ----------------------------------------------------------------------------------

up() {
  cd "$ROOT"
  check_prerequisites

  if [[ ! -f .env ]]; then
    cp .env.example .env
    warn "Created .env from .env.example (local-only values)"
  fi
  set -a; . ./.env; set +a

  bold "Starting infrastructure (Docker Compose)"
  "${COMPOSE[@]}" up -d --wait --wait-timeout 300 >/dev/null
  ok "PostgreSQL, Redis, Keycloak, S3, SQS, OpenTelemetry, Prometheus, Tempo, Grafana"

  bold "Starting services"
  start_service core-api "cd '$ROOT/core-api' && export JAVA_HOME='$JAVA25_HOME' && export PATH=\"\$JAVA_HOME/bin:\$PATH\" && \
    export MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces && \
    exec mvn -q spring-boot:run"
  start_service ai-service "cd '$ROOT/ai-service' && export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 && \
    uv sync --quiet && uv run alembic upgrade head && exec uv run uvicorn app.main:app --port 8081"
  start_service web "cd '$ROOT/web' && { [ -d node_modules ] && [ node_modules/.package-lock.json -nt package-lock.json ] || npm ci --no-audit --no-fund; } && \
    exec npm run dev -- --strictPort"

  bold "Waiting for services (the first run downloads dependencies and can take a few minutes)"
  wait_for ai-service http://localhost:8081/health/ready 150
  wait_for web http://localhost:5173 150
  wait_for core-api http://localhost:8080/actuator/health/readiness 300

  bold "Ready"
  cat <<EOF
  Web app      http://localhost:5173   user patient.a@example.test / password KC_SYNTHETIC_USER_PASSWORD in .env
  core-api     http://localhost:8080/swagger-ui.html
  ai-service   http://localhost:8081/health/ready
  Keycloak     http://localhost:8180   (admin / KC_BOOTSTRAP_ADMIN_PASSWORD)
  Grafana      http://localhost:3000   (admin / GF_SECURITY_ADMIN_PASSWORD) - dashboard "HIP - Service overview"

  Logs:  ./scripts/dev.sh logs core-api|ai-service|web
  Stop:  ./scripts/dev.sh stop
EOF
}

stop() {
  cd "$ROOT"
  bold "Stopping services"
  for service in "${SERVICES[@]}"; do stop_service "$service"; done
  bold "Stopping containers (data is kept; use 'docker compose ... down -v' to wipe it)"
  "${COMPOSE[@]}" down >/dev/null 2>&1 || true
  ok "done"
}

status() {
  cd "$ROOT"
  bold "Services"
  for service in "${SERVICES[@]}"; do
    if is_running "$service"; then ok "$service running (pid $(cat "$(pid_file "$service")"))"; else warn "$service stopped"; fi
  done
  bold "Containers"
  "${COMPOSE[@]}" ps --format '  {{.Service}}: {{.Status}}' 2>/dev/null || warn "Docker is not running"
}

logs() {
  local name=${1:-}
  [[ " ${SERVICES[*]} " == *" $name "* ]] || fail "usage: ./scripts/dev.sh logs core-api|ai-service|web"
  tail -n 100 -f "$LOG_DIR/$name.log"
}

case "${1:-up}" in
  up | start) up ;;
  stop | down) stop ;;
  status) status ;;
  logs) logs "${2:-}" ;;
  -h | --help | help) sed -n '2,10p' "$0" ;;
  *) fail "unknown command: $1 (use up, stop, status, logs)" ;;
esac
