#!/usr/bin/env bash
# Pause DiscoveryEventConsumer for S1 ACK-only sims so query backlog does not
# pollute ACK timings. HTTP ACK + Kafka produce to the request topic still run.
#
# Preferred (app flag / profile) — no scale-down of pods:
#   DISCOVERY_KAFKA_LISTENER_ENABLED=false
#   or SPRING_PROFILES_ACTIVE=...,sim-s1
#
# Alternative: scale discover Deployment to 0 replicas (blocks ACK too — only
# use when a separate ACK-only replica set keeps HTTP up). Prefer the env flag.
#
# Usage:
#   pause-discover-consumers.sh --check
#   pause-discover-consumers.sh --print
#   pause-discover-consumers.sh --apply-env     # kubectl set env (MUTATES staging)
#   pause-discover-consumers.sh --apply-scale   # kubectl scale (MUTATES — last resort)
#   pause-discover-consumers.sh --resume-env
#
# Phase-1 reliability agent is read-only kubectl — operators / chaos phase run --apply-*.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="${ROOT}/reliability/config/cluster.yaml"
ACTION="${1:---print}"

DISCOVER_DEPLOY=$(grep -E '^\s*discover:' "$CONFIG" | head -1 | sed -E 's/^[^:]*:[[:space:]]*"?([^"#\"]+)"?.*/\1/' | tr -d ' ')
DISCOVER_DEPLOY=${DISCOVER_DEPLOY:-catalog-discover-job}
# First allowlisted namespace only (ignore # e.g. "..." comments on the same line)
NS=$(grep -A20 '^namespaces:' "$CONFIG" | grep -E '^\s*-\s*"' | head -1 | sed -E 's/^[^"]*"([^"]+)".*/\1/')


print_guidance() {
  cat <<EOF
S1 consumer pause — choose ONE:

1) Preferred — disable listener via env (pods stay up for ACK):
   kubectl -n ${NS:-<discovr-ns>} set env deployment/${DISCOVER_DEPLOY} \\
     DISCOVERY_KAFKA_LISTENER_ENABLED=false
   # or: SPRING_PROFILES_ACTIVE=<existing>,sim-s1

2) Resume after S1:
   kubectl -n ${NS:-<discovr-ns>} set env deployment/${DISCOVER_DEPLOY} \\
     DISCOVERY_KAFKA_LISTENER_ENABLED=true
   # remove sim-s1 from SPRING_PROFILES_ACTIVE if added

3) Last resort — scale (also stops HTTP on that Deployment):
   kubectl -n ${NS:-<discovr-ns>} scale deployment/${DISCOVER_DEPLOY} --replicas=0
   # Only valid if ACK is served by a separate Deployment that keeps listener off.

Then set simulation.s1.consumer_paused: true in cluster.yaml for the run record.
EOF
}

check_flag() {
  if [[ -z "$NS" || "$NS" == "<FILL_DISCOVR_NAMESPACE>" ]]; then
    echo "WARN: namespaces.allowed[0] not filled in cluster.yaml — print-only." >&2
    print_guidance
    return 0
  fi
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "WARN: kubectl not available" >&2
    print_guidance
    return 0
  fi
  local env_line
  env_line=$(kubectl -n "$NS" get deploy "$DISCOVER_DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DISCOVERY_KAFKA_LISTENER_ENABLED")].value}' 2>/dev/null || true)
  local profiles
  profiles=$(kubectl -n "$NS" get deploy "$DISCOVER_DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}' 2>/dev/null || true)
  echo "deploy/$DISCOVER_DEPLOY ns=$NS"
  echo "  DISCOVERY_KAFKA_LISTENER_ENABLED=${env_line:-<unset, defaults true>}"
  echo "  SPRING_PROFILES_ACTIVE=${profiles:-<unset>}"
  if [[ "$env_line" == "false" ]] || [[ "$profiles" == *sim-s1* ]]; then
    echo "OK: consumer listener appears paused for S1"
  else
    echo "NOT PAUSED: apply --apply-env before S1 gate evidence"
    exit 1
  fi
}

apply_env() {
  [[ -n "$NS" && "$NS" != "<FILL_DISCOVR_NAMESPACE>" ]] || { echo "Fill cluster.yaml namespaces first" >&2; exit 3; }
  echo "MUTATING: set DISCOVERY_KAFKA_LISTENER_ENABLED=false on deploy/$DISCOVER_DEPLOY"
  kubectl -n "$NS" set env "deployment/${DISCOVER_DEPLOY}" DISCOVERY_KAFKA_LISTENER_ENABLED=false
  kubectl -n "$NS" rollout status "deployment/${DISCOVER_DEPLOY}" --timeout=180s
}

resume_env() {
  [[ -n "$NS" && "$NS" != "<FILL_DISCOVR_NAMESPACE>" ]] || { echo "Fill cluster.yaml namespaces first" >&2; exit 3; }
  echo "MUTATING: set DISCOVERY_KAFKA_LISTENER_ENABLED=true on deploy/$DISCOVER_DEPLOY"
  kubectl -n "$NS" set env "deployment/${DISCOVER_DEPLOY}" DISCOVERY_KAFKA_LISTENER_ENABLED=true
  kubectl -n "$NS" rollout status "deployment/${DISCOVER_DEPLOY}" --timeout=180s
}

apply_scale() {
  [[ -n "$NS" && "$NS" != "<FILL_DISCOVR_NAMESPACE>" ]] || { echo "Fill cluster.yaml namespaces first" >&2; exit 3; }
  echo "WARNING: scaling discover to 0 also stops ACK HTTP on this Deployment." >&2
  kubectl -n "$NS" scale "deployment/${DISCOVER_DEPLOY}" --replicas=0
}

case "$ACTION" in
  --print) print_guidance ;;
  --check) check_flag ;;
  --apply-env) apply_env ;;
  --resume-env) resume_env ;;
  --apply-scale) apply_scale ;;
  *)
    echo "Usage: $0 --print|--check|--apply-env|--resume-env|--apply-scale" >&2
    exit 2
    ;;
esac
