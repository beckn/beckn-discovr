#!/usr/bin/env bash
# Simulation dispatcher — S1 + corpus-backed S3/S5/S6.
#
# Usage:
#   sim-run.sh S1 --profile 1M --tpm 12000 --registry-mode cache-hit [--dry-run]
#   sim-run.sh S1 --profile 1M --intent intent-j.json --registry-mode cache-hit
#   sim-run.sh S3 --profile 1M --path T --qps 200 [--dry-run]
#   sim-run.sh S3 --profile 1M --path mix --qps 200   # sequential weighted sub-runs
#   sim-run.sh S5 --profile 1M --path G --headline-only --qps 200 [--dry-run]
#   sim-run.sh S6 --profile 1M --mode sample [--dry-run]
#
# Corpus profiles: local-smoke | 1M | 10M | 50M | 100M
# Design: Agent Store docs/corpus-ladder-simulation.md
#
# NOTE: S3/S5 JMeter samples measure HTTP ACK. Engine / e2e p99 come from
# ClickStack (or Micrometer) — sim-run records a clickstack hint in verdict.md.

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
INTENT=""                 # set by --intent; not overwritten unless --path set
PATH_MODE=""              # empty = no path mapping (S1 default intent-t)
HEADLINE_ONLY=0
S6_MODE="sample"
SKIP_CORPUS_CHECK=0
PATH_EXPLICIT=0
INTENT_EXPLICIT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --tpm) TPM="${2:?}"; shift 2 ;;
    --peak) PEAK="${2:?}"; shift 2 ;;
    --qps) QPS="${2:?}"; shift 2 ;;
    --registry-mode) REGISTRY_MODE="${2:?}"; shift 2 ;;
    --duration-sec) DURATION_SEC="${2:?}"; shift 2 ;;
    --users) USERS="${2:?}"; shift 2 ;;
    --intent) INTENT="${2:?}"; INTENT_EXPLICIT=1; shift 2 ;;
    --path) PATH_MODE="${2:?}"; PATH_EXPLICIT=1; shift 2 ;;
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
PUSH_SAMPLE="reliability/simulations/fixtures/push/push-sample.json"

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

# Default path for corpus-backed sims when neither --path nor --intent given
if [[ "$PATH_EXPLICIT" -eq 0 && "$INTENT_EXPLICIT" -eq 0 ]]; then
  case "$SIM_ID" in
    S3|S5) PATH_MODE="T"; PATH_EXPLICIT=1 ;;
    S1) INTENT="intent-t.json" ;;
    S6) INTENT="push-sample.json" ;;
  esac
fi

# Map --path → intent only when --path was provided (never clobber lone --intent)
query_path="${PATH_MODE:-T}"
RUN_MIX=0
if [[ "$PATH_EXPLICIT" -eq 1 ]]; then
  case "$PATH_MODE" in
    T|t) INTENT="intent-t.json"; query_path="T" ;;
    G|g) INTENT="intent-g.json"; query_path="G" ;;
    J|j) INTENT="intent-j.json"; query_path="J" ;;
    JG|jg|J+G|j+g) INTENT="intent-jg.json"; query_path="J+G" ;;
    mix|MIX)
      RUN_MIX=1
      query_path="mix"
      INTENT="intent-t.json"  # first sub-run; mix expands below
      ;;
    *)
      echo "ERROR: --path must be T|G|J|JG|mix" >&2
      exit 2
      ;;
  esac
elif [[ "$INTENT_EXPLICIT" -eq 1 ]]; then
  query_path="custom"
fi

[[ -n "$INTENT" ]] || INTENT="intent-t.json"

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
ack_only_jtl: $([[ "$SIM_ID" == "S3" || "$SIM_ID" == "S5" ]] && echo true || echo false)
engine_e2e_source: clickstack
EOF

echo "=== ${SIM_ID} ==="
echo "  corpus_profile=${PROFILE} qps≈${target_qps} users=${USERS} duration=${DURATION_SEC}s path=${query_path}"
echo "  report=${REPORT_DIR}"

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

write_verdict() {
  local status="$1"
  local extra="$2"
  cat >"$REPORT_DIR/verdict.md" <<EOF
# ${SIM_ID} verdict

Status: **${status}**

Corpus profile: \`${PROFILE}\`
Query path: \`${query_path}\`

${extra}

## Measurement notes

- JMeter JTL for S3/S5 is **ACK HTTP latency**, not engine/e2e p99.
- Engine PG/ES p99 and e2e \`on_discover\` p99: query ClickStack via
  \`reliability/scripts/clickstack-query.sh\` (fill \`clickstack.base_url\` in cluster.yaml).
- Label all metrics with \`corpus.profile=${PROFILE}\` and \`query.path\`.
EOF
}

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo
  echo "DRY-RUN — guardrail / file check only (no kubectl, no SSH)."
  case "$SIM_ID" in
    S1)
      echo "Would pause consumers + run $JMX_REMOTE intent=$INTENT"
      write_verdict "NOT RUN" "$(grep -A20 '^S1:' "$SLO_FILE" | sed 's/^/  /')"
      ;;
    S3)
      echo "Would require corpus-verify (ES_URL+PG_URL) + consumers ENABLED + run $JMX_REMOTE"
      if [[ "$RUN_MIX" -eq 1 ]]; then
        echo "Would expand --path mix into sequential weighted sub-runs: T40% G25% J20% JG10% of duration"
      fi
      write_verdict "NOT RUN" "SLO: ES BM25 p99 ≤ 200 ms; PG p99 ≤ 150 ms (ClickStack)."
      ;;
    S5)
      echo "Would require corpus-verify + full stack + run $JMX_REMOTE"
      write_verdict "NOT RUN" "SLO: e2e headline p99 ≤ 1.5 s (ClickStack); success ≥ 99.9%."
      ;;
    S6)
      echo "Would sync push-sample.json and run $JMX_REMOTE against PUSH_URL"
      echo "  hint: ./reliability/scripts/corpus-seed.sh --profile ${PROFILE} --mode publish --dry-run"
      write_verdict "NOT RUN" "SLO: push→searchable p99 2–5 s; ES bulk reject 0."
      ;;
  esac
  echo "Would run:"
  echo "  reliability/scripts/jmeter-trigger.sh ${SIM_ID} ${JMX_REMOTE} ${JMETER_ARGS[*]}"
  echo "Wrote $REPORT_DIR/verdict.md"
  exit 0
fi

# Live path — corpus gate first (S3/S5/S6 require verified indexed corpus)
if [[ "$SIM_ID" != "S1" && "$SKIP_CORPUS_CHECK" -eq 0 ]]; then
  if [[ -z "${ES_URL:-}" || -z "${PG_URL:-}" ]]; then
    echo "ERROR: ES_URL and PG_URL required for ${SIM_ID} (require_corpus_verified)." >&2
    echo "  Export them, or pass --skip-corpus-check only for intentional bypass." >&2
    exit 3
  fi
fi

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
  reliability/scripts/corpus-verify.sh --profile "$PROFILE" || {
    echo "ERROR: corpus-verify failed for profile $PROFILE" >&2
    exit 1
  }
fi

reliability/scripts/verify-cluster.sh "$CONFIG"

sync_fixtures() {
  echo "Syncing JMX + fixtures to ${USER}@${VM}:${SCRIPTS_DIR}"
  gcloud compute ssh "${USER}@${VM}" --zone "$ZONE" --command "mkdir -p '${SCRIPTS_DIR}'"
  gcloud compute scp --zone "$ZONE" "$JMX_REPO" "${USER}@${VM}:${SCRIPTS_DIR}/${JMX_REMOTE}"
  for intent in "$INTENT_T" "$INTENT_J" "$INTENT_G" "$INTENT_JG"; do
    base=$(basename "$intent")
    gcloud compute scp --zone "$ZONE" "$intent" "${USER}@${VM}:${SCRIPTS_DIR}/${base}"
  done
  if [[ -f "$PUSH_SAMPLE" ]]; then
    gcloud compute scp --zone "$ZONE" "$PUSH_SAMPLE" "${USER}@${VM}:${SCRIPTS_DIR}/push-sample.json"
  fi
}

if [[ "$SKIP_SYNC" -eq 0 ]]; then
  sync_fixtures
fi

echo '{"sim":"'"$SIM_ID"'","phase":"pre","corpus_profile":"'"$PROFILE"'"}' >"$REPORT_DIR/pre.json"

run_one_jmeter() {
  local label="$1"
  local intent_file="$2"
  local path_label="$3"
  local dur="$4"
  local args=(
    -Jusers="$USERS"
    -Jduration="$dur"
    -Jramp=60
    -JthinkTimeMs=0
    -JcorpusProfile="$PROFILE"
    -JqueryPath="$path_label"
    -JregistryMode="$REGISTRY_MODE"
    -JdiscoverUrl="${DISCOVER_URL}"
    -JintentPath="$intent_file"
  )
  echo "--- JMeter ${SIM_ID} path=${path_label} intent=${intent_file} duration=${dur}s ---"
  reliability/scripts/jmeter-trigger.sh "${SIM_ID}-${path_label}" "$JMX_REMOTE" "${args[@]}"
}

JC=0
set +e
if [[ "$SIM_ID" == "S6" ]]; then
  reliability/scripts/jmeter-trigger.sh "$SIM_ID" "$JMX_REMOTE" "${JMETER_ARGS[@]}"
  JC=$?
elif [[ "$RUN_MIX" -eq 1 && ( "$SIM_ID" == "S3" || "$SIM_ID" == "S5" ) ]]; then
  # Weighted sequential mix (~architecture planning mix without semantic)
  # T 40% / G 25% / J 20% / JG 10%  (remaining 5% folded into T)
  local_t=$(( DURATION_SEC * 45 / 100 ))
  local_g=$(( DURATION_SEC * 25 / 100 ))
  local_j=$(( DURATION_SEC * 20 / 100 ))
  local_jg=$(( DURATION_SEC - local_t - local_g - local_j ))
  [[ "$local_jg" -lt 30 ]] && local_jg=30
  run_one_jmeter T intent-t.json T "$local_t"; JC=$?
  if [[ "$JC" -eq 0 ]]; then run_one_jmeter G intent-g.json G "$local_g"; JC=$?; fi
  if [[ "$JC" -eq 0 ]]; then run_one_jmeter J intent-j.json J "$local_j"; JC=$?; fi
  if [[ "$JC" -eq 0 ]]; then run_one_jmeter JG intent-jg.json J+G "$local_jg"; JC=$?; fi
else
  reliability/scripts/jmeter-trigger.sh "$SIM_ID" "$JMX_REMOTE" "${JMETER_ARGS[@]}"
  JC=$?
fi
set -e

echo "{\"sim\":\"${SIM_ID}\",\"phase\":\"post\",\"jmeter_exit\":${JC},\"corpus_profile\":\"${PROFILE}\"}" >"$REPORT_DIR/post.json"

# Optional ClickStack reminder (does not fail the run if unconfigured)
CLICK_HINT="Paste engine/e2e p99 from ClickStack into this verdict."
if grep -q 'base_url:.*<FILL' "$CONFIG" 2>/dev/null; then
  CLICK_HINT="clickstack.base_url still placeholder — configure then run clickstack-query.sh for engine/e2e p99."
fi

write_verdict "PENDING_MEASUREMENT" \
  "| Field | Value |
|-------|-------|
| JMeter exit | ${JC} |
| Corpus profile | ${PROFILE} |
| Query path | ${query_path} |
| Target QPS | ${target_qps} |

${CLICK_HINT}
Mark PASS only if \`${SLO_FILE}\` rows for ${SIM_ID} hold **and** corpus-verify passed for \`${PROFILE}\`."

echo "Wrote $REPORT_DIR/verdict.md"
exit "$JC"
