#!/usr/bin/env bash
# Deploy / configure a WireMock (or compatible) registry stub for S1 ACK sims.
#
# Registry URL shape (Beckn auth SDK):
#   {SIGNATURE_REGISTRY_BASE_URL}/{subscriberId}/{registryName}/{uniqueKeyId}
# Lookup: GET /subscribers/{subscriberId}/keys/{uniqueKeyId}
#
# Modes (design §4.4):
#   cache-hit      — 0 ms delay; JMX uses stable uniqueKeyId after warm cache
#   cache-miss     — fixed delay 80–150 ms (default 120); JMX rotates uniqueKeyId
#                    every sample so discover auth cache cannot absorb RTT
#   registry-down  — HTTP 503; expect NACK / fail-closed
#
# WireMock reads <root-dir>/mappings/*.json — generated files go to
#   reliability/simulations/wiremock/__generated/mappings/
# Templates under wiremock/templates/ are NOT mounted (avoids priority collisions).
#
# Staging: point simulation.registry_mock_url at an **in-cluster** WireMock Service.
# 127.0.0.1 from this laptop is not reachable from discover pods.
#
# Usage:
#   mock-registry.sh start --mode cache-hit [--port 8089] [--key-b64 <spki>]
#   mock-registry.sh write-mappings --mode cache-miss
#   mock-registry.sh stop | status | print-env
#
# Never logs Authorization headers or signing private keys.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WM_DIR="${ROOT}/reliability/simulations/wiremock"
GEN_ROOT="${WM_DIR}/__generated"
GEN_MAP="${GEN_ROOT}/mappings"
PID_FILE="${WM_DIR}/.wiremock.pid"
LOG_FILE="${WM_DIR}/wiremock.log"
CONFIG="${ROOT}/reliability/config/cluster.yaml"
MODE="cache-hit"
PORT="${MOCK_REGISTRY_PORT:-8089}"
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
  mkdir -p "$GEN_MAP"
  rm -f "$GEN_MAP"/*.json

  if [[ "$MODE" == "registry-down" ]]; then
    cat >"$GEN_MAP/registry.json" <<'EOF'
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
    echo "Wrote single mapping $GEN_MAP/registry.json (mode=registry-down)"
    return
  fi

  local delay=0
  if [[ "$MODE" == "cache-miss" ]]; then
    delay="$MISS_DELAY_MS"
  fi

  cat >"$GEN_MAP/registry.json" <<EOF
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
  echo "Wrote single mapping $GEN_MAP/registry.json (mode=$MODE delayMs=$delay)"
}

cluster_mock_url() {
  local u
  u=$(grep -E '^\s*registry_mock_url:' "$CONFIG" 2>/dev/null | head -1 | sed -E 's/.*:\s*"([^"]+)".*/\1/' || true)
  if [[ -n "$u" && "$u" != \<FILL_* ]]; then
    echo "$u"
  else
    echo "http://mock-registry.<discovr-ns>.svc.cluster.local:8080/subscribers"
  fi
}

print_env() {
  local base
  base="$(cluster_mock_url)"
  cat <<EOF
# Point catalog-discover-job at the **in-cluster** mock (not 127.0.0.1 from laptop):
# simulation.registry_mock_url / SIGNATURE_REGISTRY_BASE_URL=${base}
export SIGNATURE_AUTH_ENABLED=true
export SIGNATURE_REGISTRY_BASE_URL=${base}
export SIGNATURE_REGISTRY_NAME=keys
# cache-miss: JMX rotates uniqueKeyId per sample; TTL alone is insufficient at 200 QPS
export SIGNATURE_CACHE_TTL_SECONDS=$([ "$MODE" = "cache-miss" ] && echo 1 || echo 2592000)
# Local docker-only smoke (Compose on same host as discover):
#   SIGNATURE_REGISTRY_BASE_URL=http://127.0.0.1:${PORT}/subscribers
EOF
}

start_local() {
  write_mappings
  if command -v docker >/dev/null 2>&1; then
    local name="discovr-s1-mock-registry"
    docker rm -f "$name" >/dev/null 2>&1 || true
    # Official image loads /home/wiremock/mappings — mount only the generated mappings dir
    docker run -d --name "$name" -p "${PORT}:8080" \
      -v "${GEN_MAP}:/home/wiremock/mappings:ro" \
      wiremock/wiremock:3.3.1 >/dev/null
    echo "Started WireMock container $name on :${PORT} (mode=$MODE)"
    echo "LOCAL_SMOKE_URL=http://127.0.0.1:${PORT}/subscribers"
    echo "STAGING_URL=$(cluster_mock_url)  # deploy the same mappings into the cluster Service"
    print_env
    return
  fi

  if command -v wiremock >/dev/null 2>&1 || [[ -n "${WIREMOCK_JAR:-}" ]]; then
    local jar="${WIREMOCK_JAR:-}"
    # --root-dir must contain a mappings/ subdirectory (WireMock convention)
    local cmd
    if [[ -n "$jar" ]]; then
      cmd=(java -jar "$jar" --port "$PORT" --root-dir "$GEN_ROOT")
    else
      cmd=(wiremock --port "$PORT" --root-dir "$GEN_ROOT")
    fi
    "${cmd[@]}" >"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
    echo "Started local WireMock pid=$(cat "$PID_FILE") on :${PORT} (mode=$MODE root=$GEN_ROOT)"
    print_env
    return
  fi

  echo "WARN: neither docker nor wiremock CLI found." >&2
  echo "Wrote $GEN_MAP — copy into your in-cluster WireMock mappings volume." >&2
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
  write-mappings) write_mappings ;;
  *)
    echo "Usage: $0 {start|stop|status|print-env|write-mappings} [--mode cache-hit|cache-miss|registry-down] [--port N]" >&2
    exit 2
    ;;
esac
