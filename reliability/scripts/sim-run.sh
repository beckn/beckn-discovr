#!/usr/bin/env bash
# Simulation dispatcher — S1 + corpus-backed S3/S5/S6.
#
# Usage:
#   sim-run.sh S1 --profile 1M --tpm 12000 --registry-mode cache-hit [--dry-run]
#   sim-run.sh S3 --profile 1M --path mix --qps 200 [--dry-run]
#   sim-run.sh S5 --profile 1M --headline-only --qps 200 [--dry-run]
#   sim-run.sh S6 --profile 1M --mode sample [--dry-run]
#
# Corpus profiles: local-smoke | 1M | 10M | 50M | 100M
# Design: Agent Store docs/corpus-ladder-simulation.md

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

CONFIG="reliability/config/cluster.yaml"
SLO_FILE="reliability/config/slos-100m.yaml"
PROFILES_FILE="reliability/config/corpus-profiles.yaml"

SIM_ID="${1:?sim id required (S1|S3|S5|S6)}"
shift

PROFILE="1M"
TPM="12000"
PEAK=""
QPS=""
REGISTRY_MODE="cache-hit"
DRY_RUN=0
SKIP_SYNC=0
DURATION_SEC=""
USERS=""
INTENT="intent-t.json"
PATH_MODE="T"          # T|G|J|JG|mix
HEADLINE_ONLY=0
S6_MODE="sample"
SKIP_CORPUS_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --tpm) TPM="${2:?}"; shift 2 ;;
    --peak) PEAK="${2:?}"; shift 2 ;;
    --qps) QPS="${2:?}"; shift 2 ;;
    --registry-mode) REGISTRY_MODE="${2:?}"; shift 2 ;;
    --duration-sec) DURATION_SEC="${2:?}"; shift 2 ;;
    --users) USERS="${2:?}"; shift 2 ;;
    --intent) INTENT="${2:?}"; shift 2 ;;
    --path) PATH_MODE="${2:?}"; shift 2 ;;
    --headline-only) HEADLINE_ONLY=1; shift ;;
    --mode) S6_MODE="${2:?}"; shift 2 ;;
    --skip-corpus-check) SKIP_CORPUS_CHECK=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-sync) SKIP_SYNC=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

case "$SIM_ID" in
  S1|S3|S5|S6) ;;
  S2|S4)
    echo "ERROR: $SIM_ID driver not in this slice (kafka-inject / mock-onix follow-on)." >&2
    exit 2
    ;;
  *)
    echo "ERROR: unsupported sim id: $SIM_ID (want S1|S3|S5|S6)" >&2
    exit 2
    ;;
esac

case "$PROFILE" in
  local-smoke|1M|10M|50M|100M|15M) ;;
  *) echo "ERROR: --profile must be local-smoke|1M|10M|50M|100M (got $PROFILE)" >&2; exit 2 ;;
esac

case "$REGISTRY_MODE" in
  cache-hit|cache-miss|registry-down) ;;
  *) echo "ERROR: --registry-mode must be cache-hit|cache-miss|registry-down" >&2; exit 2 ;;
esac

INTENT_T="reliability/simulations/fixtures/intents/intent-t.json"
INTENT_J="reliability/simulations/fixtures/intents/intent-j.json"
INTENT_G="reliability/simulations/fixtures/intents/intent-g.json"
INTENT_JG="reliability/simulations/fixtures/intents/intent-jg.json"

for f in "$CONFIG" "$SLO_FILE" "$PROFILES_FILE" "$INTENT_T" \
         "reliability/scripts/jmeter-trigger.sh" "reliability/scripts/verify-cluster.sh"; do
  [[ -f "$f" ]] || { echo "ERROR: missing $f" >&2; exit 2; }
done

read_yaml() {
  grep -E "^\s*$1:" "$CONFIG" | head -1 | sed -E "s/.*$1:[[:space:]]*\"([^\"]+)\".*/\1/"
}

# Resolve target QPS
if [[ -n "$QPS" ]]; then
  target_qps="$QPS"
elif [[ -n "$PEAK" ]]; then
  target_qps="$PEAK"
else
  target_qps=$(( TPM / 60 ))
fi
if [[ -z "$USERS" ]]; then
  USERS=$(( target_qps / 4 ))
  [[ "$USERS" -lt 10 ]] && USERS=10
fi
if [[ -z "$DURATION_SEC" ]]; then
  DURATION_SEC=600
fi

# Map --path → intent file + label
query_path="$PATH_MODE"
case "$PATH_MODE" in
  T|t) INTENT="intent-t.json"; query_path="T" ;;
  G|g) INTENT="intent-g.json"; query_path="G" ;;
  J|j) INTENT="intent-j.json"; query_path="J" ;;
  JG|jg|J+G|j+g) INTENT="intent-jg.json"; query_path="J+G" ;;
  mix|MIX)
    INTENT="intent-t.json"
    query_path="mix"
    ;;
  *)
    if [[ "$SIM_ID" != "S1" && "$SIM_ID" != "S6" ]]; then
      echo "ERROR: --path must be T|G|J|JG|mix" >&2
      exit 2
    fi
    ;;
esac

DISCOVER_URL=$(read_yaml discovr_discover)
PUSH_URL=$(read_yaml discovr_push)
VM=$(read_yaml vm_name)
ZONE=$(read_yaml zone)
USER=$(read_yaml user)
SCRIPTS_DIR=$(read_yaml scripts_dir)

TS=$(date -u +%Y%m%dT%H%M%SZ)
REPORT_DIR="reliability/reports/${TS}/sim-${SIM_ID}-${PROFILE}"
mkdir -p "$REPORT_DIR"

case "$SIM_ID" in
  S1) JMX_REPO="reliability/jmx/s1-ack-only.jmx"; JMX_REMOTE="s1-ack-only.jmx" ;;
  S3) JMX_REPO="reliability/jmx/s3-query-headline.jmx"; JMX_REMOTE="s3-query-headline.jmx" ;;
  S5) JMX_REPO="reliability/jmx/s5-e2e-headline.jmx"; JMX_REMOTE="s5-e2e-headline.jmx" ;;
  S6) JMX_REPO="reliability/jmx/s6-publish-burst.jmx"; JMX_REMOTE="s6-publish-burst.jmx" ;;
esac
[[ -f "$JMX_REPO" ]] || { echo "ERROR: missing $JMX_REPO" >&2; exit 2; }

cat >"$REPORT_DIR/run-meta.yaml" <<EOF
sim: ${SIM_ID}
corpus_profile: ${PROFILE}
tpm: ${TPM}
target_qps: ${target_qps}
peak_override: ${PEAK:-null}
qps_override: ${QPS:-null}
registry_mode: ${REGISTRY_MODE}
query_path: ${query_path}
headline_only: ${HEADLINE_ONLY}
s6_mode: ${S6_MODE}
users: ${USERS}
duration_sec: ${DURATION_SEC}
intent: ${INTENT}
discover_url: ${DISCOVER_URL}
push_url: ${PUSH_URL}
started_at_utc: ${TS}
slo_file: ${SLO_FILE}
dry_run: ${DRY_RUN}
consumers_expected: $([[ "$SIM_ID" == "S1" ]] && echo paused || echo enabled)
EOF

echo "=== ${SIM_ID} ==="
echo "  corpus_profile=${PROFILE} qps≈${target_qps} users=${USERS} duration=${DURATION_SEC}s path=${query_path}"
echo "  report=${REPORT_DIR}"

if [[ "$SIM_ID" != "S1" && "$SKIP_CORPUS_CHECK" -eq 0 ]]; then
  echo "Corpus check: prefer ./reliability/scripts/corpus-verify.sh --profile ${PROFILE} before live runs."
fi

JMETER_ARGS=(
  -Jusers="$USERS"
  -Jduration="$DURATION_SEC"
  -Jramp=60
  -JthinkTimeMs=0
  -JcorpusProfile="$PROFILE"
  -JqueryPath="$query_path"
  -JregistryMode="$REGISTRY_MODE"
)

if [[ "$SIM_ID" == "S6" ]]; then
  JMETER_ARGS+=(-JpushUrl="${PUSH_URL}" -JintentPath="push-sample.json")
else
  JMETER_ARGS+=(-JdiscoverUrl="${DISCOVER_URL}" -JintentPath="$INTENT")
fi

write_verdict_dry() {
  local extra="$1"
  cat >"$REPORT_DIR/verdict.md" <<EOF
# ${SIM_ID} verdict (dry-run)

Status: **NOT RUN** (dry-run)

Corpus profile: \`${PROFILE}\`
Query path: \`${query_path}\`

${extra}

Fill after a live run. Report p99 **and** throughput with this corpus size labeled.
EOF
}

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo
  echo "DRY-RUN — guardrail / file check only (no kubectl, no SSH)."
  case "$SIM_ID" in
    S1)
      echo "Would pause consumers + run $JMX_REMOTE"
      write_verdict_dry "$(grep -A20 '^S1:' "$SLO_FILE" | sed 's/^/  /')"
      ;;
    S3)
      echo "Would require corpus-verify + consumers ENABLED + run $JMX_REMOTE"
      echo "Would sync intents: intent-t/g/j/jg.json"
      write_verdict_dry "SLO: ES BM25 p99 ≤ 200 ms; PG p99 ≤ 150 ms (see S3 in $SLO_FILE)."
      ;;
    S5)
      echo "Would require corpus-verify + full stack + run $JMX_REMOTE"
      write_verdict_dry "SLO: e2e headline p99 ≤ 1.5 s; success ≥ 99.9%."
      ;;
    S6)
      echo "Would run corpus-seed publish sample and/or $JMX_REMOTE against PUSH_URL"
      echo "  hint: ./reliability/scripts/corpus-seed.sh --profile ${PROFILE} --mode publish --dry-run"
      write_verdict_dry "SLO: push→searchable p99 2–5 s; ES bulk reject 0."
      ;;
  esac
  echo "Would run:"
  echo "  reliability/scripts/jmeter-trigger.sh ${SIM_ID} ${JMX_REMOTE} ${JMETER_ARGS[*]}"
  echo "Wrote $REPORT_DIR/verdict.md"
  exit 0
fi

# Live path
if [[ -z "$VM" || "$VM" == "<FILL_JMETER_VM_NAME>" ]]; then
  echo "ERROR: jmeter.vm_name not set in $CONFIG — use --dry-run or fill cluster.yaml" >&2
  exit 3
fi

if [[ "$SIM_ID" == "S6" ]]; then
  if [[ -z "$PUSH_URL" || "$PUSH_URL" == "<FILL_DISCOVR_PUSH_URL>" ]]; then
    echo "ERROR: endpoints.discovr_push not set in $CONFIG" >&2
    exit 3
  fi
else
  if [[ -z "$DISCOVER_URL" || "$DISCOVER_URL" == "<FILL_DISCOVR_DISCOVER_URL>" ]]; then
    echo "ERROR: endpoints.discovr_discover not set in $CONFIG" >&2
    exit 3
  fi
fi

if [[ "$SIM_ID" != "S1" && "$SKIP_CORPUS_CHECK" -eq 0 ]]; then
  if [[ -n "${ES_URL:-}" && -n "${PG_URL:-}" ]]; then
    reliability/scripts/corpus-verify.sh --profile "$PROFILE" || {
      echo "ERROR: corpus-verify failed for profile $PROFILE" >&2
      exit 1
    }
  else
    echo "WARN: ES_URL/PG_URL unset — skipping live corpus-verify (set --skip-corpus-check to silence)"
  fi
fi

reliability/scripts/verify-cluster.sh "$CONFIG"

if [[ "$SKIP_SYNC" -eq 0 ]]; then
  echo "Syncing JMX + intents to ${USER}@${VM}:${SCRIPTS_DIR}"
  gcloud compute ssh "${USER}@${VM}" --zone "$ZONE" --command "mkdir -p '${SCRIPTS_DIR}'"
  gcloud compute scp --zone "$ZONE" "$JMX_REPO" "${USER}@${VM}:${SCRIPTS_DIR}/${JMX_REMOTE}"
  for intent in "$INTENT_T" "$INTENT_J" "$INTENT_G" "$INTENT_JG"; do
    base=$(basename "$intent")
    gcloud compute scp --zone "$ZONE" "$intent" "${USER}@${VM}:${SCRIPTS_DIR}/${base}"
  done
fi

echo '{"sim":"'"$SIM_ID"'","phase":"pre","corpus_profile":"'"$PROFILE"'"}' >"$REPORT_DIR/pre.json"

set +e
reliability/scripts/jmeter-trigger.sh "$SIM_ID" "$JMX_REMOTE" "${JMETER_ARGS[@]}"
JC=$?
set -e

echo "{\"sim\":\"${SIM_ID}\",\"phase\":\"post\",\"jmeter_exit\":${JC},\"corpus_profile\":\"${PROFILE}\"}" >"$REPORT_DIR/post.json"

cat >"$REPORT_DIR/verdict.md" <<EOF
# ${SIM_ID} verdict

| Field | Value |
|-------|-------|
| JMeter exit | ${JC} |
| Corpus profile | ${PROFILE} |
| Query path | ${query_path} |
| Target QPS | ${target_qps} |

**Operator:** paste measured p99 / throughput from JTL + ClickStack.
Mark PASS only if \`${SLO_FILE}\` rows for ${SIM_ID} hold **and** corpus-verify passed for \`${PROFILE}\`.

Status: **PENDING_MEASUREMENT**
EOF

echo "Wrote $REPORT_DIR/verdict.md"
exit "$JC"
