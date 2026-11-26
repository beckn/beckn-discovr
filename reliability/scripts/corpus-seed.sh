#!/usr/bin/env bash
# Generate and load a corpus ladder profile into ES + PG (or publish via HTTP).
#
# Usage:
#   corpus-seed.sh --profile 1M --dry-run
#   corpus-seed.sh --profile local-smoke --mode bulk
#   corpus-seed.sh --profile local-smoke --mode publish
#   corpus-seed.sh --profile 1M --mode bulk --limit 10000   # partial rung
#
# Env:
#   ES_URL          e.g. http://localhost:9200
#   PG_URL          libpq URI e.g. postgres://user:pass@host:5434/discovr
#   PUSH_URL        catalog push endpoint (publish mode)
#   CORPUS_WORK_DIR scratch dir (default /tmp/discovr-corpus-<profile>)
#
# Design: Agent Store docs/corpus-ladder-simulation.md

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROFILE=""
MODE="bulk"          # bulk | publish | generate-only
DRY_RUN=0
LIMIT=""
BATCH_SIZE=500
WORK_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --mode) MODE="${2:?}"; shift 2 ;;
    --limit) LIMIT="${2:?}"; shift 2 ;;
    --batch-size) BATCH_SIZE="${2:?}"; shift 2 ;;
    --work-dir) WORK_DIR="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$PROFILE" ]] || { echo "ERROR: --profile required (local-smoke|1M|10M|50M|100M)" >&2; exit 2; }

GEN="$ROOT/reliability/scripts/corpus-generate.py"
[[ -x "$GEN" || -f "$GEN" ]] || { echo "ERROR: missing $GEN" >&2; exit 2; }
chmod +x "$GEN" 2>/dev/null || true

case "$MODE" in
  bulk|publish|generate-only) ;;
  *) echo "ERROR: --mode must be bulk|publish|generate-only" >&2; exit 2 ;;
esac

LIMIT_ARGS=()
[[ -n "$LIMIT" ]] && LIMIT_ARGS=(--limit "$LIMIT")

echo "=== corpus-seed ==="
echo "  profile=$PROFILE mode=$MODE dry_run=$DRY_RUN limit=${LIMIT:-full}"
python3 "$GEN" --profile "$PROFILE" --format summary "${LIMIT_ARGS[@]}"

SUMMARY=$(python3 "$GEN" --profile "$PROFILE" --format summary "${LIMIT_ARGS[@]}")
ES_INDEX=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['es_index'])")
RESOURCES=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['effective_resources'])")
PREFIX=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['catalog_id_prefix'])")

WORK_DIR="${WORK_DIR:-/tmp/discovr-corpus-${PROFILE}}"
mkdir -p "$WORK_DIR"

if [[ "$DRY_RUN" -eq 1 ]]; then
  cat <<EOF

DRY-RUN — no ES/PG/HTTP writes.

Would:
  1. Stream ES NDJSON into index '${ES_INDEX}' (${RESOURCES} docs) via ${ES_URL:-\$ES_URL}/_bulk
  2. COPY ${RESOURCES} rows into PG item (+ locations) via ${PG_URL:-\$PG_URL}
     catalog_id prefix '${PREFIX}-*'
  3. Run corpus-verify.sh --profile ${PROFILE}

For publish mode: write push batches under ${WORK_DIR}/push and POST to \$PUSH_URL.

Agent / small VM tip: start with
  ./reliability/scripts/corpus-seed.sh --profile local-smoke --mode bulk
then on GCP for 1M:
  ./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk
EOF
  exit 0
fi

case "$PROFILE" in
  10M|50M|100M)
    if [[ -z "${CORPUS_ALLOW_LARGE:-}" ]]; then
      echo "ERROR: profile ${PROFILE} refuses to run without CORPUS_ALLOW_LARGE=1" >&2
      echo "Set that env after confirming ES/PG hardware matches corpus-profiles.yaml." >&2
      exit 3
    fi
    ;;
esac

if [[ "$MODE" == "generate-only" || "$MODE" == "bulk" ]]; then
  echo "Generating ES NDJSON → ${WORK_DIR}/es.ndjson (streaming may be huge — prefer pipe load)"
fi

load_es_bulk() {
  local es_url="${ES_URL:?ES_URL required for bulk mode}"
  echo "Ensuring index ${ES_INDEX} exists on ${es_url}"
  curl -sS -o /dev/null -w "%{http_code}" -X PUT "${es_url}/${ES_INDEX}" \
    -H 'Content-Type: application/json' \
    -d '{"settings":{"refresh_interval":"-1","number_of_replicas":0}}' || true
  echo

  echo "Streaming bulk index (batch ${BATCH_SIZE} docs)…"
  local batch_file="${WORK_DIR}/bulk-batch.ndjson"
  local count=0
  local batch_docs=0
  : >"$batch_file"
  # shellcheck disable=SC2068
  while IFS= read -r line; do
    echo "$line" >>"$batch_file"
    # every two lines = one doc
    if [[ $((++count % 2)) -eq 0 ]]; then
      batch_docs=$((batch_docs + 1))
      if [[ "$batch_docs" -ge "$BATCH_SIZE" ]]; then
        curl -sS -H 'Content-Type: application/x-ndjson' \
          -X POST "${es_url}/_bulk" --data-binary @"$batch_file" \
          | python3 -c "import sys,json; r=json.load(sys.stdin); 
print('bulk_errors', r.get('errors'), 'items', len(r.get('items',[])));
assert not r.get('errors'), 'ES bulk had errors'"
        : >"$batch_file"
        batch_docs=0
      fi
    fi
  done < <(python3 "$GEN" --profile "$PROFILE" --format es-ndjson "${LIMIT_ARGS[@]}")

  if [[ -s "$batch_file" ]]; then
    curl -sS -H 'Content-Type: application/x-ndjson' \
      -X POST "${es_url}/_bulk" --data-binary @"$batch_file" \
      | python3 -c "import sys,json; r=json.load(sys.stdin); assert not r.get('errors')"
  fi
  curl -sS -X POST "${es_url}/${ES_INDEX}/_refresh" >/dev/null
  echo "ES bulk complete; refresh issued"
}

load_pg_bulk() {
  local pg_url="${PG_URL:?PG_URL required for bulk mode}"
  command -v psql >/dev/null || { echo "ERROR: psql required for bulk PG load" >&2; exit 3; }

  echo "COPY item rows via STDIN (prefix ${PREFIX})…"
  # Delete prior rung rows for this prefix to avoid double-count
  psql "$pg_url" -v ON_ERROR_STOP=1 -c \
    "DELETE FROM item_location_collection WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';
     DELETE FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';"

  # Stream generator → COPY FROM STDIN (avoids multi-GB temp files at 1M+)
  # shellcheck disable=SC2068
  python3 "$GEN" --profile "$PROFILE" --format pg-copy "${LIMIT_ARGS[@]}" \
    | psql "$pg_url" -v ON_ERROR_STOP=1 -c \
      "\\copy item (id, catalog_id, context_url, type, network_id, offer_ids, payload) FROM STDIN"

  local loc_tsv="${WORK_DIR}/loc.tsv"
  # shellcheck disable=SC2068
  python3 "$GEN" --profile "$PROFILE" --format pg-loc-copy "${LIMIT_ARGS[@]}" >"$loc_tsv"
  if [[ -s "$loc_tsv" ]]; then
    psql "$pg_url" -v ON_ERROR_STOP=1 <<SQL
CREATE TEMP TABLE tmp_sim_loc (
  item_id text, catalog_id text, path text, lon double precision, lat double precision
);
\\copy tmp_sim_loc FROM '${loc_tsv}'
INSERT INTO item_location_collection (item_id, catalog_id, path, geom)
SELECT item_id, catalog_id, path, ST_SetSRID(ST_MakePoint(lon, lat), 4326)
FROM tmp_sim_loc
ON CONFLICT DO NOTHING;
SQL
  fi
  echo "PG bulk complete"
}

publish_http() {
  local push_url="${PUSH_URL:?PUSH_URL required for publish mode}"
  local push_dir="${WORK_DIR}/push"
  # shellcheck disable=SC2068
  python3 "$GEN" --profile "$PROFILE" --format push-batch --out-dir "$push_dir" \
    --batch-size "$BATCH_SIZE" "${LIMIT_ARGS[@]}"
  local n=0
  for f in "$push_dir"/push-*.json; do
    [[ -f "$f" ]] || continue
    code=$(curl -sS -o /tmp/corpus-push-resp.json -w "%{http_code}" \
      -X POST "$push_url" -H 'Content-Type: application/json' --data-binary @"$f")
    echo "POST $f → HTTP $code"
    n=$((n + 1))
  done
  echo "Published ${n} batches to ${push_url}"
}

case "$MODE" in
  generate-only)
    # shellcheck disable=SC2068
    python3 "$GEN" --profile "$PROFILE" --format es-ndjson "${LIMIT_ARGS[@]}" \
      >"${WORK_DIR}/es.ndjson"
    # shellcheck disable=SC2068
    python3 "$GEN" --profile "$PROFILE" --format pg-copy "${LIMIT_ARGS[@]}" \
      >"${WORK_DIR}/item.tsv"
    echo "Wrote ${WORK_DIR}/es.ndjson and item.tsv"
    ;;
  bulk)
    load_es_bulk
    load_pg_bulk
    "$ROOT/reliability/scripts/corpus-verify.sh" --profile "$PROFILE" ${LIMIT:+--expect "$RESOURCES"}
    ;;
  publish)
    publish_http
    echo "NOTE: wait for publish→searchable lag, then run corpus-verify.sh"
    ;;
esac

echo "DONE profile=${PROFILE} mode=${MODE}"
