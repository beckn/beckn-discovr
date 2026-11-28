#!/usr/bin/env bash
# Deploy / configure a WireMock (or compatible) registry stub for S1 ACK sims.
#
# Registry URL shape (Beckn auth SDK):
#   {SIGNATURE_REGISTRY_BASE_URL}/{subscriberId}/{registryName}/{uniqueKeyId}
# Example base: http://mock-registry:8080/subscribers
# Lookup:      GET /subscribers/{subscriberId}/keys/{uniqueKeyId}
#
# Modes (design §4.4):
#   cache-hit      — 0 ms fixed delay; prime discover auth cache with a warm-up
#   cache-miss     — fixed delay 80–150 ms (default 120); use unique keyIds or
#                    short SIGNATURE_CACHE_TTL_SECONDS so each ACK pays RTT
#   registry-down  — HTTP 503; expect NACK / fail-closed, not hang
#
# Usage:
#   mock-registry.sh start --mode cache-hit [--port 8089] [--key-b64 <spki>]
#   mock-registry.sh stop
#   mock-registry.sh status
#   mock-registry.sh print-env --mode cache-miss
#
# Never logs Authorization headers or signing private keys.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WM_DIR="${ROOT}/reliability/simulations/wiremock"
PID_FILE="${WM_DIR}/.wiremock.pid"
LOG_FILE="${WM_DIR}/wiremock.log"
MODE="cache-hit"
PORT="${MOCK_REGISTRY_PORT:-8089}"
# Ed25519 SPKI (X.509) base64 — replace in staging with the sim BAP's real public key
# when auth is enabled. Default is a non-secret test placeholder for dry-run wiring.
KEY_B64="${MOCK_REGISTRY_KEY_B64:-MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqoltrGCBjr+A6vxHTw=}"
MISS_DELAY_MS="${MOCK_REGISTRY_MISS_DELAY_MS:-120}"
ACTION="${1:-}"
shift || true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --key-b64) KEY_B64="${2:?}"; shift 2 ;;
    --miss-delay-ms) MISS_DELAY_MS="${2:?}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

case "$MODE" in
  cache-hit|cache-miss|registry-down) ;;
  *) echo "ERROR: --mode must be cache-hit|cache-miss|registry-down" >&2; exit 2 ;;
esac

write_mappings() {
  local out="${WM_DIR}/__generated"
  mkdir -p "$out"
  rm -f "$out"/*.json

  if [[ "$MODE" == "registry-down" ]]; then
    cat >"$out/registry-down.json" <<'EOF'
{
  "priority": 1,
  "request": { "method": "GET", "urlPathPattern": "/subscribers/.*/keys/.*" },
  "response": {
    "status": 503,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "error": "registry unavailable (S1 registry-down)" },
    "fixedDelayMilliseconds": 50
  }
}
EOF
    return
  fi

  local delay=0
  if [[ "$MODE" == "cache-miss" ]]; then
    delay="$MISS_DELAY_MS"
  fi

  cat >"$out/registry-keys.json" <<EOF
{
  "priority": 1,
  "request": { "method": "GET", "urlPathPattern": "/subscribers/.*/keys/.*" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "data": [
        { "state": "live", "signing_public_key": "${KEY_B64}" }
      ]
    },
    "fixedDelayMilliseconds": ${delay}
  }
}
EOF
}

print_env() {
  local base="http://127.0.0.1:${PORT}/subscribers"
  cat <<EOF
# Point catalog-discover-job at the mock (never commit secrets):
export SIGNATURE_AUTH_ENABLED=true
export SIGNATURE_REGISTRY_BASE_URL=${base}
export SIGNATURE_REGISTRY_NAME=keys
# cache-miss drills: keep TTL short or rotate uniqueKeyId so RTT is paid
export SIGNATURE_CACHE_TTL_SECONDS=$([ "$MODE" = "cache-miss" ] && echo 1 || echo 2592000)
# Optional: SPRING_PROFILES_ACTIVE=sim-s1  (pauses DiscoveryEventConsumer)
EOF
}

start_local() {
  write_mappings
  if command -v docker >/dev/null 2>&1; then
    local name="discovr-s1-mock-registry"
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --name "$name" -p "${PORT}:8080" \
      -v "${WM_DIR}/__generated:/home/wiremock/mappings:ro" \
      wiremock/wiremock:3.3.1 --global-response-templating >/dev/null
    echo "Started WireMock container $name on :${PORT} (mode=$MODE)"
    echo "MOCK_REGISTRY_URL=http://127.0.0.1:${PORT}/subscribers"
    print_env
    return
  fi

  if command -v wiremock >/dev/null 2>&1 || [[ -n "${WIREMOCK_JAR:-}" ]]; then
    local jar="${WIREMOCK_JAR:-}"
    local cmd
    if [[ -n "$jar" ]]; then
      cmd=(java -jar "$jar" --port "$PORT" --root-dir "$WM_DIR/__generated")
    else
      cmd=(wiremock --port "$PORT" --root-dir "$WM_DIR/__generated")
    fi
    "${cmd[@]}" >"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
    echo "Started local WireMock pid=$(cat "$PID_FILE") on :${PORT} (mode=$MODE)"
    print_env
    return
  fi

  echo "WARN: neither docker nor wiremock CLI found." >&2
  echo "Wrote mappings under ${WM_DIR}/__generated — deploy them to your staging WireMock." >&2
  echo "Staging: set simulation.registry_mock_url in cluster.yaml to that service." >&2
  print_env
}

stop_local() {
  if [[ -f "$PID_FILE" ]]; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "Stopped local WireMock process"
  fi
  if command -v docker >/dev/null 2>&1; then
    docker rm -f discovr-s1-mock-registry >/dev/null 2>&1 || true
    echo "Removed docker container discovr-s1-mock-registry (if present)"
  fi
}

status() {
  if curl -sf "http://127.0.0.1:${PORT}/__admin/mappings" >/dev/null 2>&1; then
    echo "OK: WireMock admin reachable on :${PORT}"
  else
    echo "DOWN: nothing on :${PORT}/__admin"
    exit 1
  fi
}

case "$ACTION" in
  start) start_local ;;
  stop) stop_local ;;
  status) status ;;
  print-env) print_env ;;
  write-mappings) write_mappings; echo "Wrote ${WM_DIR}/__generated" ;;
  *)
    echo "Usage: $0 {start|stop|status|print-env|write-mappings} [--mode cache-hit|cache-miss|registry-down] [--port N]" >&2
    exit 2
    ;;
esac
