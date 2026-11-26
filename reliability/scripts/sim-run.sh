#!/usr/bin/env bash
# Simulation dispatcher — S1 only in this PR (S2–S6 follow-on).
#
# Usage:
#   sim-run.sh S1 --profile 15M --tpm 12000 --registry-mode cache-hit [--dry-run]
#   sim-run.sh S1 --profile 15M --peak 600 --registry-mode cache-miss
#
# Reuses verify-cluster.sh + jmeter-trigger.sh guardrails. Syncs
# reliability/jmx/s1-ack-only.jmx and intent fixtures to the JMeter VM scripts_dir
# unless --skip-sync.
#
# Design: Agent Store docs/simulation-harness-design.md §12

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

CONFIG="reliability/config/cluster.yaml"
SLO_FILE="reliability/config/slos-100m.yaml"
JMX_REPO="reliability/jmx/s1-ack-only.jmx"
INTENT_T="reliability/simulations/fixtures/intents/intent-t.json"
INTENT_J="reliability/simulations/fixtures/intents/intent-j.json"

SIM_ID="${1:?sim id required (S1)}"
shift

PROFILE="15M"
TPM="12000"
PEAK=""
REGISTRY_MODE="cache-hit"
DRY_RUN=0
SKIP_SYNC=0
DURATION_SEC=""
USERS=""
INTENT="intent-t.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --tpm) TPM="${2:?}"; shift 2 ;;
    --peak) PEAK="${2:?}"; shift 2 ;;
    --registry-mode) REGISTRY_MODE="${2:?}"; shift 2 ;;
    --duration-sec) DURATION_SEC="${2:?}"; shift 2 ;;
    --users) USERS="${2:?}"; shift 2 ;;
    --intent) INTENT="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-sync) SKIP_SYNC=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ "$SIM_ID" != "S1" ]]; then
  echo "ERROR: only S1 is implemented in this slice. Got: $SIM_ID" >&2
  echo "S2–S6 land in follow-on PRs per simulation-harness-design.md §12." >&2
  exit 2
fi

case "$REGISTRY_MODE" in
  cache-hit|cache-miss|registry-down) ;;
  *) echo "ERROR: --registry-mode must be cache-hit|cache-miss|registry-down" >&2; exit 2 ;;
esac

for f in "$CONFIG" "$SLO_FILE" "$JMX_REPO" "$INTENT_T" "reliability/scripts/jmeter-trigger.sh" \
         "reliability/scripts/verify-cluster.sh" "reliability/simulations/S1-ack-only.md"; do
  [[ -f "$f" ]] || { echo "ERROR: missing $f" >&2; exit 2; }
done

read_yaml() {
  grep -E "^\s*$1:" "$CONFIG" | head -1 | sed -E "s/.*$1:[[:space:]]*\"([^\"]+)\".*/\1/"
}

# QPS → thread count heuristic (override with --users). Default: users ≈ qps / 4
# with thinkTime 0; tune on staging after first run.
target_qps=$(( TPM / 60 ))
if [[ -n "$PEAK" ]]; then
  target_qps="$PEAK"
fi
if [[ -z "$USERS" ]]; then
  USERS=$(( target_qps / 4 ))
  [[ "$USERS" -lt 10 ]] && USERS=10
fi
if [[ -z "$DURATION_SEC" ]]; then
  DURATION_SEC=600   # 10 min sustain per design default step
fi

DISCOVER_URL=$(read_yaml discovr_discover)
VM=$(read_yaml vm_name)
ZONE=$(read_yaml zone)
USER=$(read_yaml user)
SCRIPTS_DIR=$(read_yaml scripts_dir)
RESULTS_DIR=$(read_yaml results_dir)

TS=$(date -u +%Y%m%dT%H%M%SZ)
REPORT_DIR="reliability/reports/${TS}/sim-S1-${REGISTRY_MODE}"
mkdir -p "$REPORT_DIR"

cat >"$REPORT_DIR/run-meta.yaml" <<EOF
sim: S1
profile: ${PROFILE}
tpm: ${TPM}
target_qps: ${target_qps}
peak_override: ${PEAK:-null}
registry_mode: ${REGISTRY_MODE}
users: ${USERS}
duration_sec: ${DURATION_SEC}
intent: ${INTENT}
discover_url: ${DISCOVER_URL}
started_at_utc: ${TS}
slo_file: ${SLO_FILE}
dry_run: ${DRY_RUN}
EOF

echo "=== S1 ACK-only ==="
echo "  profile=${PROFILE} tpm=${TPM} qps≈${target_qps} users=${USERS} duration=${DURATION_SEC}s"
echo "  registry_mode=${REGISTRY_MODE}"
echo "  report=${REPORT_DIR}"

JMETER_ARGS=(
  -Jusers="$USERS"
  -Jduration="$DURATION_SEC"
  -Jramp=60
  -JthinkTimeMs=0
  -JdiscoverUrl="${DISCOVER_URL}"
  -JintentPath="$INTENT"
  -JregistryMode="$REGISTRY_MODE"
)

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo
  echo "DRY-RUN — guardrail / file check only (no kubectl, no SSH)."
  echo "Would verify cluster context via reliability/scripts/verify-cluster.sh"
  echo "Would sync: $JMX_REPO + intents → ${SCRIPTS_DIR:-<scripts_dir>} on VM ${VM:-<vm>}"
  echo "Would run:"
  echo "  reliability/scripts/jmeter-trigger.sh S1 s1-ack-only.jmx ${JMETER_ARGS[*]}"
  echo
  # Soft SLO echo for operators
  echo "SLO targets (from $SLO_FILE):"
  grep -A20 '^S1:' "$SLO_FILE" | sed 's/^/  /'
  cat >"$REPORT_DIR/verdict.md" <<EOF
# S1 verdict (dry-run)

Status: **NOT RUN** (dry-run)

Registry mode: \`${REGISTRY_MODE}\`
Profile: \`${PROFILE}\`

Fill after a live run against staging. Compare JTL ACK p99 to:

| Metric | Target |
|--------|--------|
| ACK p99 cache-hit | ≤ 100 ms |
| ACK p99 cache-miss | ≤ 250 ms |
| ACK error rate | ≤ 0.1% |
| Kafka produce p99 | ≤ 20 ms |

Consumers must be paused — see \`reliability/scripts/pause-discover-consumers.sh\`.
EOF
  echo "Wrote $REPORT_DIR/verdict.md"
  exit 0
fi

# Live path
if [[ -z "$VM" || "$VM" == "<FILL_JMETER_VM_NAME>" ]]; then
  echo "ERROR: jmeter.vm_name not set in $CONFIG — use --dry-run or fill cluster.yaml" >&2
  exit 3
fi
if [[ -z "$DISCOVER_URL" || "$DISCOVER_URL" == "<FILL_DISCOVR_DISCOVER_URL>" ]]; then
  echo "ERROR: endpoints.discovr_discover not set in $CONFIG" >&2
  exit 3
fi

reliability/scripts/verify-cluster.sh "$CONFIG"

if [[ "$SKIP_SYNC" -eq 0 ]]; then
  echo "Syncing JMX + intents to ${USER}@${VM}:${SCRIPTS_DIR}"
  gcloud compute ssh "${USER}@${VM}" --zone "$ZONE" --command "mkdir -p '${SCRIPTS_DIR}'"
  gcloud compute scp --zone "$ZONE" "$JMX_REPO" "${USER}@${VM}:${SCRIPTS_DIR}/s1-ack-only.jmx"
  gcloud compute scp --zone "$ZONE" "$INTENT_T" "${USER}@${VM}:${SCRIPTS_DIR}/intent-t.json"
  gcloud compute scp --zone "$ZONE" "$INTENT_J" "${USER}@${VM}:${SCRIPTS_DIR}/intent-j.json"
fi

# Capture placeholder pre.json (ClickStack optional — agent fills on full runs)
echo '{"sim":"S1","phase":"pre","note":"extend with clickstack-query.sh snapshots"}' >"$REPORT_DIR/pre.json"

set +e
reliability/scripts/jmeter-trigger.sh S1 s1-ack-only.jmx "${JMETER_ARGS[@]}"
JC=$?
set -e

echo "{\"sim\":\"S1\",\"phase\":\"post\",\"jmeter_exit\":${JC}}" >"$REPORT_DIR/post.json"

HIT_P99=$(grep -E 'cache_hit_p99_ms:' "$SLO_FILE" | head -1 | awk '{print $2}')
MISS_P99=$(grep -E 'cache_miss_p99_ms:' "$SLO_FILE" | head -1 | awk '{print $2}')
ERR_MAX=$(grep -E 'error_rate_max:' "$SLO_FILE" | head -1 | awk '{print $2}')
PROD_P99=$(grep -E 'produce_p99_ms:' "$SLO_FILE" | head -1 | awk '{print $2}')

TARGET_P99="$HIT_P99"
[[ "$REGISTRY_MODE" == "cache-miss" ]] && TARGET_P99="$MISS_P99"

cat >"$REPORT_DIR/verdict.md" <<EOF
# S1 verdict

| Field | Value |
|-------|-------|
| JMeter exit | ${JC} |
| Registry mode | ${REGISTRY_MODE} |
| Profile | ${PROFILE} |
| Target QPS | ${target_qps} |
| ACK p99 target | ≤ ${TARGET_P99} ms |
| Error rate max | ${ERR_MAX} |
| Kafka produce p99 max | ${PROD_P99} ms |

**Operator:** paste measured ACK p99 / error rate / produce p99 from JTL + ClickStack.
Mark PASS only if all S1 rows in \`${SLO_FILE}\` hold and consumers were paused.

Status: **PENDING_MEASUREMENT**
EOF

echo "Wrote $REPORT_DIR/verdict.md"
exit "$JC"
