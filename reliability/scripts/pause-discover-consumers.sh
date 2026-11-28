#!/usr/bin/env bash
# Pause DiscoveryEventConsumer for S1 ACK-only sims so query backlog does not
# pollute ACK timings. HTTP ACK + Kafka produce to the request topic still run.
#
# Preferred (app flag / profile) — no scale-down of pods:
#   DISCOVERY_KAFKA_LISTENER_ENABLED=false
#   or SPRING_PROFILES_ACTIVE=...,sim-s1
#
# On resume, offsets are reset to **latest** before re-enabling the listener so
# the S1 backlog (~targetRps × duration) is not replayed into the query path /
# callbacks to fixture bapUri (sim-bap.example.com). Alternative: use a sim-only
# request topic via KAFKA_REQUEST_TOPIC for the S1 Deployment.
#
# Usage:
#   pause-discover-consumers.sh --check
#   pause-discover-consumers.sh --print
#   pause-discover-consumers.sh --apply-env
#   pause-discover-consumers.sh --resume-env          # reset offsets → then enable
#   pause-discover-consumers.sh --resume-env --skip-offset-reset
#   pause-discover-consumers.sh --apply-scale
#
# Mutating actions always run verify-cluster.sh first and pass --context.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="${ROOT}/reliability/config/cluster.yaml"
ACTION="${1:---print}"
shift || true
SKIP_OFFSET_RESET=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-offset-reset) SKIP_OFFSET_RESET=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

DISCOVER_DEPLOY=$(grep -E '^\s*discover:' "$CONFIG" | head -1 | sed -E 's/^[^:]*:[[:space:]]*"?([^"#\"]+)"?.*/\1/' | tr -d ' ')
DISCOVER_DEPLOY=${DISCOVER_DEPLOY:-catalog-discover-job}
NS=$(grep -A20 '^namespaces:' "$CONFIG" | grep -E '^\s*-\s*"' | head -1 | sed -E 's/^[^"]*"([^"]+)".*/\1/')
CTX=$(grep -E '^\s*context:' "$CONFIG" | head -1 | sed -E 's/.*:\s*"([^"]+)".*/\1/')
CONSUMER_GROUP=$(grep -A20 'consumer_groups:' "$CONFIG" | grep -E '^\s*discover:' | head -1 | sed -E 's/.*:\s*"([^"]+)".*/\1/')
CONSUMER_GROUP=${CONSUMER_GROUP:-catalog-discover-job}
# Prefer simulation.topics.discover_in; fall back to kafka.topics.discover_events
TOPIC=$(grep -A10 '^\s*topics:' "$CONFIG" | grep -E '^\s*discover_in:' | head -1 | sed -E 's/.*:\s*"([^"]+)".*/\1/' || true)
if [[ -z "${TOPIC:-}" || "$TOPIC" == \<FILL_* ]]; then
  TOPIC=$(grep -E '^\s*discover_events:' "$CONFIG" | head -1 | sed -E 's/.*:\s*"([^"]+)".*/\1/' || true)
fi

kubectl_ctx() {
  if [[ -n "$CTX" && "$CTX" != "<FILL_KUBECTL_CONTEXT>" ]]; then
    kubectl --context "$CTX" "$@"
  else
    kubectl "$@"
  fi
}

require_cluster() {
  "${ROOT}/reliability/scripts/verify-cluster.sh" "$CONFIG"
  [[ -n "$NS" && "$NS" != "<FILL_DISCOVR_NAMESPACE>" ]] || {
    echo "Fill cluster.yaml namespaces.allowed[0] first" >&2
    exit 3
  }
}

print_guidance() {
  cat <<EOF
S1 consumer pause — choose ONE:

1) Preferred — disable listener via env (pods stay up for ACK):
   kubectl --context ${CTX:-<ctx>} -n ${NS:-<discovr-ns>} set env deployment/${DISCOVER_DEPLOY} \\
     DISCOVERY_KAFKA_LISTENER_ENABLED=false

2) Resume after S1 (resets offsets to latest, then enables listener):
   ./reliability/scripts/pause-discover-consumers.sh --resume-env
   # skips replay of S1 backlog into query/callback path

3) Last resort — scale (also stops HTTP on that Deployment):
   kubectl --context ${CTX:-<ctx>} -n ${NS:-<discovr-ns>} scale deployment/${DISCOVER_DEPLOY} --replicas=0

Alt: dedicate a sim request topic (KAFKA_REQUEST_TOPIC) so S1 never shares prod offsets.

Then set simulation.s1.consumer_paused: true in cluster.yaml for the run record.
EOF
}

check_flag() {
  if [[ -z "$NS" || "$NS" == "<FILL_DISCOVR_NAMESPACE>" ]]; then
    echo "WARN: namespaces.allowed[0] not filled — print-only." >&2
    print_guidance
    return 0
  fi
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "WARN: kubectl not available" >&2
    print_guidance
    return 0
  fi
  local env_line profiles
  env_line=$(kubectl_ctx -n "$NS" get deploy "$DISCOVER_DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DISCOVERY_KAFKA_LISTENER_ENABLED")].value}' 2>/dev/null || true)
  profiles=$(kubectl_ctx -n "$NS" get deploy "$DISCOVER_DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}' 2>/dev/null || true)
  echo "deploy/$DISCOVER_DEPLOY ns=$NS context=${CTX:-<current>}"
  echo "  DISCOVERY_KAFKA_LISTENER_ENABLED=${env_line:-<unset, defaults true>}"
  echo "  SPRING_PROFILES_ACTIVE=${profiles:-<unset>}"
  if [[ "$env_line" == "false" ]] || [[ "$profiles" == *sim-s1* ]]; then
    echo "OK: consumer listener appears paused for S1"
  else
    echo "NOT PAUSED: apply --apply-env before S1 gate evidence"
    exit 1
  fi
}

reset_offsets_to_latest() {
  if [[ "$SKIP_OFFSET_RESET" -eq 1 ]]; then
    echo "Skipping offset reset (--skip-offset-reset)"
    return 0
  fi
  if [[ -z "${TOPIC:-}" || "$TOPIC" == \<FILL_* ]]; then
    echo "WARN: discover request topic not filled in cluster.yaml — cannot auto-reset offsets." >&2
    echo "  Set simulation.topics.discover_in or pass a sim-only KAFKA_REQUEST_TOPIC." >&2
    echo "  Refusing to re-enable listener without --skip-offset-reset (would replay S1 backlog)." >&2
    exit 4
  fi
  echo "Resetting consumer group '${CONSUMER_GROUP}' on topic '${TOPIC}' to latest (before enabling listener)"
  # Prefer kafka CLI in a broker/tools pod; operators may override via env.
  local tools_deploy="${KAFKA_TOOLS_POD_SELECTOR:-app=kafka}"
  local pod
  pod=$(kubectl_ctx -n "$NS" get pods -l "$tools_deploy" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  if [[ -z "$pod" ]]; then
    # Try kafka namespace from allowlist (second entry often kafka)
    local kns
    kns=$(grep -A20 '^namespaces:' "$CONFIG" | grep -E '^\s*-\s*"' | sed -n '2p' | sed -E 's/^[^"]*"([^"]+)".*/\1/')
    if [[ -n "$kns" && "$kns" != \<FILL_* ]]; then
      pod=$(kubectl_ctx -n "$kns" get pods -l "$tools_deploy" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
      [[ -n "$pod" ]] && NS_KAFKA="$kns"
    fi
  fi
  local exec_ns="${NS_KAFKA:-$NS}"
  if [[ -z "$pod" ]]; then
    echo "ERROR: no Kafka tools pod found to reset offsets." >&2
    echo "  Manually: kafka-consumer-groups.sh --bootstrap-server \$BS \\" >&2
    echo "    --group ${CONSUMER_GROUP} --topic ${TOPIC} --reset-offsets --to-latest --execute" >&2
    echo "  Then re-run with --skip-offset-reset after manual reset." >&2
    exit 5
  fi
  local bs="${KAFKA_BOOTSTRAP:-kafka:9092}"
  kubectl_ctx -n "$exec_ns" exec "$pod" -- \
    kafka-consumer-groups.sh --bootstrap-server "$bs" \
      --group "$CONSUMER_GROUP" --topic "$TOPIC" \
      --reset-offsets --to-latest --execute
}

apply_env() {
  require_cluster
  echo "MUTATING: set DISCOVERY_KAFKA_LISTENER_ENABLED=false on deploy/$DISCOVER_DEPLOY (context=$CTX)"
  kubectl_ctx -n "$NS" set env "deployment/${DISCOVER_DEPLOY}" DISCOVERY_KAFKA_LISTENER_ENABLED=false
  kubectl_ctx -n "$NS" rollout status "deployment/${DISCOVER_DEPLOY}" --timeout=180s
}

resume_env() {
  require_cluster
  reset_offsets_to_latest
  echo "MUTATING: set DISCOVERY_KAFKA_LISTENER_ENABLED=true on deploy/$DISCOVER_DEPLOY (context=$CTX)"
  kubectl_ctx -n "$NS" set env "deployment/${DISCOVER_DEPLOY}" DISCOVERY_KAFKA_LISTENER_ENABLED=true
  kubectl_ctx -n "$NS" rollout status "deployment/${DISCOVER_DEPLOY}" --timeout=180s
}

apply_scale() {
  require_cluster
  echo "WARNING: scaling discover to 0 also stops ACK HTTP on this Deployment." >&2
  kubectl_ctx -n "$NS" scale "deployment/${DISCOVER_DEPLOY}" --replicas=0
}

case "$ACTION" in
  --print) print_guidance ;;
  --check) check_flag ;;
  --apply-env) apply_env ;;
  --resume-env) resume_env ;;
  --apply-scale) apply_scale ;;
  *)
    echo "Usage: $0 --print|--check|--apply-env|--resume-env|--apply-scale [--skip-offset-reset]" >&2
    exit 2
    ;;
esac
