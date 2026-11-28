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
#   ES_ALIAS        discover alias (default: beckn-catalog)
#   PG_URL          libpq URI e.g. postgres://user:pass@host:5434/discovr
#   PUSH_URL        catalog push endpoint (publish mode)
#   CORPUS_WORK_DIR scratch dir (default /tmp/discovr-corpus-<profile>)
#   CORPUS_ALLOW_LARGE=1  required for 10M|15M|50M|100M
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
ES_ALIAS="${ES_ALIAS:-beckn-catalog}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --mode) MODE="${2:?}"; shift 2 ;;
    --limit) LIMIT="${2:?}"; shift 2 ;;
    --batch-size) BATCH_SIZE="${2:?}"; shift 2 ;;
    --work-dir) WORK_DIR="${2:?}"; shift 2 ;;
    --es-alias) ES_ALIAS="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$PROFILE" ]] || { echo "ERROR: --profile required (local-smoke|1M|10M|50M|100M)" >&2; exit 2; }

GEN="$ROOT/reliability/scripts/corpus-generate.py"
[[ -f "$GEN" ]] || { echo "ERROR: missing $GEN" >&2; exit 2; }
chmod +x "$GEN" 2>/dev/null || true

case "$MODE" in
  bulk|publish|generate-only) ;;
  *) echo "ERROR: --mode must be bulk|publish|generate-only" >&2; exit 2 ;;
esac

LIMIT_ARGS=()
[[ -n "$LIMIT" ]] && LIMIT_ARGS=(--limit "$LIMIT")

echo "=== corpus-seed ==="
echo "  profile=$PROFILE mode=$MODE dry_run=$DRY_RUN limit=${LIMIT:-full} es_alias=$ES_ALIAS"
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
  1. Create index '${ES_INDEX}' (beckn-catalog-* prefix + geo_shape/BM25 mappings) then bulk ${RESOURCES} docs
  2. PUT alias '${ES_ALIAS}' → '${ES_INDEX}' (so discover can search it)
  3. Restore refresh_interval to 5s after bulk
  4. COPY ${RESOURCES} rows into PG item (+ locations) via \${PG_URL}
     catalog_id prefix '${PREFIX}-*'; ilc.path = \$.catalogs[*].resources[*].availableAt[*].geo
  5. Run corpus-verify.sh --profile ${PROFILE} (counts via alias + golden query probes)

For publish mode: write push batches under ${WORK_DIR}/push and POST to \$PUSH_URL
(fail on non-2xx); poll searchable when possible.

Agent / small VM tip: start with
  ./reliability/scripts/corpus-seed.sh --profile local-smoke --mode bulk
then on GCP for 1M:
  ./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk
EOF
  exit 0
fi

case "$PROFILE" in
  10M|15M|50M|100M)
    if [[ -z "${CORPUS_ALLOW_LARGE:-}" ]]; then
      echo "ERROR: profile ${PROFILE} refuses to run without CORPUS_ALLOW_LARGE=1" >&2
      echo "Set that env after confirming ES/PG hardware matches corpus-profiles.yaml." >&2
      exit 3
    fi
    ;;
esac

load_es_bulk() {
  local es_url="${ES_URL:?ES_URL required for bulk mode}"
  echo "Ensuring index ${ES_INDEX} exists on ${es_url}"
  # Prefer production template (beckn-catalog-*) via index name prefix.
  # Also embed minimal geo_shape + full_text_blob mappings so a fresh sim ES
  # without the publish-job template still supports BM25 + geo probes.
  curl -sS -o /tmp/corpus-es-create.json -w "%{http_code}" -X PUT "${es_url}/${ES_INDEX}" \
    -H 'Content-Type: application/json' \
    -d @- >/tmp/corpus-es-create.code <<'EOF' || true
{
  "settings": {
    "refresh_interval": "-1",
    "number_of_replicas": 0,
    "analysis": {
      "filter": {
        "english_stop": {"type": "stop", "stopwords": "_english_"},
        "english_stemmer": {"type": "stemmer", "language": "english"}
      },
      "analyzer": {
        "beckn_text": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "english_stop", "english_stemmer"]
        },
        "beckn_text_search": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "english_stop", "english_stemmer"]
        }
      }
    }
  },
  "mappings": {
    "dynamic_templates": [
      {"geo_fields": {"path_match": "*.geo", "mapping": {"type": "geo_shape"}}}
    ],
    "properties": {
      "catalog_id": {"type": "keyword"},
      "resource_id": {"type": "keyword"},
      "network_id": {"type": "keyword"},
      "resource_name": {"type": "text", "fields": {"raw": {"type": "keyword"}}},
      "full_text_blob": {
        "type": "text",
        "analyzer": "beckn_text",
        "search_analyzer": "beckn_text_search"
      },
      "loc_catalogs_resources_availableAt": {
        "properties": {
          "geo": {"type": "geo_shape"}
        }
      }
    }
  }
}
EOF
  echo "  create HTTP $(cat /tmp/corpus-es-create.code)"

  # Disable refresh for bulk even when index already existed
  curl -sS -X PUT "${es_url}/${ES_INDEX}/_settings" \
    -H 'Content-Type: application/json' \
    -d '{"index":{"refresh_interval":"-1","number_of_replicas":0}}' >/dev/null

  echo "Streaming bulk via Python (batch ${BATCH_SIZE})…"
  # Avoid bash line-by-line — won't scale to 100M
  # shellcheck disable=SC2068
  python3 - "$GEN" "$PROFILE" "$es_url" "$ES_INDEX" "$BATCH_SIZE" "${LIMIT_ARGS[@]}" <<'PY'
import json, subprocess, sys, urllib.request

gen, profile, es_url, index, batch_size = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
limit_args = sys.argv[6:]
cmd = ["python3", gen, "--profile", profile, "--format", "es-ndjson", *limit_args]
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)
buf = []
docs = 0
batches = 0

def flush():
    global buf, batches
    if not buf:
        return
    body = "".join(buf).encode()
    req = urllib.request.Request(
        f"{es_url.rstrip('/')}/_bulk",
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        result = json.loads(resp.read().decode())
    if result.get("errors"):
        # Surface first error item
        for item in result.get("items", []):
            for op, meta in item.items():
                if meta.get("error"):
                    raise SystemExit(f"ES bulk error: {meta['error']}")
        raise SystemExit("ES bulk reported errors=true")
    batches += 1
    buf = []

assert proc.stdout is not None
for line in proc.stdout:
    buf.append(line)
    # action + source = 2 lines per doc
    if len(buf) % 2 == 0 and (len(buf) // 2) >= batch_size:
        docs += len(buf) // 2
        flush()
if buf:
    docs += len(buf) // 2
    flush()
rc = proc.wait()
if rc != 0:
    raise SystemExit(f"generator exited {rc}")
print(f"ES bulk posted docs={docs} batches={batches}")
PY

  echo "Attaching alias ${ES_ALIAS} → ${ES_INDEX}"
  curl -sS -X POST "${es_url}/_aliases" \
    -H 'Content-Type: application/json' \
    -d "{\"actions\":[{\"add\":{\"index\":\"${ES_INDEX}\",\"alias\":\"${ES_ALIAS}\"}}]}" \
    | python3 -c "import sys,json; r=json.load(sys.stdin); assert r.get('acknowledged', False), r"

  echo "Restoring refresh_interval=5s"
  curl -sS -X PUT "${es_url}/${ES_INDEX}/_settings" \
    -H 'Content-Type: application/json' \
    -d '{"index":{"refresh_interval":"5s"}}' >/dev/null
  curl -sS -X POST "${es_url}/${ES_INDEX}/_refresh" >/dev/null
  echo "ES bulk complete; alias + refresh restored"
}

load_pg_bulk() {
  local pg_url="${PG_URL:?PG_URL required for bulk mode}"
  command -v psql >/dev/null || { echo "ERROR: psql required for bulk PG load" >&2; exit 3; }

  echo "COPY item rows via STDIN (prefix ${PREFIX})…"
  psql "$pg_url" -v ON_ERROR_STOP=1 -c \
    "DELETE FROM item_location_collection WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';
     DELETE FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';"

  # shellcheck disable=SC2068
  python3 "$GEN" --profile "$PROFILE" --format pg-copy "${LIMIT_ARGS[@]}" \
    | psql "$pg_url" -v ON_ERROR_STOP=1 -c \
      "\\copy item (id, catalog_id, context_url, type, network_id, offer_ids, payload) FROM STDIN"

  # Stream locations: SQL preamble + TSV + trailer on one psql stdin (no huge temp file)
  # shellcheck disable=SC2068
  {
    cat <<'SQL'
CREATE TEMP TABLE tmp_sim_loc (
  item_id text, catalog_id text, path text, lon double precision, lat double precision
);
COPY tmp_sim_loc FROM STDIN;
SQL
    python3 "$GEN" --profile "$PROFILE" --format pg-loc-copy "${LIMIT_ARGS[@]}"
    printf '\\.\n'
    cat <<'SQL'
INSERT INTO item_location_collection (item_id, catalog_id, path, geom)
SELECT item_id, catalog_id, path, ST_SetSRID(ST_MakePoint(lon, lat), 4326)
FROM tmp_sim_loc
ON CONFLICT DO NOTHING;
SQL
  } | psql "$pg_url" -v ON_ERROR_STOP=1
  echo "PG bulk complete"
}

publish_http() {
  local push_url="${PUSH_URL:?PUSH_URL required for publish mode}"
  local push_dir="${WORK_DIR}/push"
  # shellcheck disable=SC2068
  python3 "$GEN" --profile "$PROFILE" --format push-batch --out-dir "$push_dir" \
    --batch-size "$BATCH_SIZE" "${LIMIT_ARGS[@]}"
  local n=0
  local fail=0
  local t0 t1
  t0=$(date +%s%3N)
  for f in "$push_dir"/push-*.json; do
    [[ -f "$f" ]] || continue
    code=$(curl -sS -o /tmp/corpus-push-resp.json -w "%{http_code}" \
      -X POST "$push_url" -H 'Content-Type: application/json' --data-binary @"$f" || echo "000")
    echo "POST $f → HTTP $code"
    if [[ "$code" != 2* ]]; then
      echo "ERROR: publish failed HTTP $code body=$(head -c 200 /tmp/corpus-push-resp.json)" >&2
      fail=1
    fi
    n=$((n + 1))
  done
  t1=$(date +%s%3N)
  echo "Published ${n} batches to ${push_url} elapsed_ms=$((t1 - t0))"
  [[ "$fail" -eq 0 ]] || exit 1
  echo "NOTE: wait for publish→searchable lag, then run corpus-verify.sh"
  echo "      (push→searchable p99 is operator-measured from ClickStack / poll loops)"
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
    ES_ALIAS="$ES_ALIAS" "$ROOT/reliability/scripts/corpus-verify.sh" --profile "$PROFILE" ${LIMIT:+--expect "$RESOURCES"}
    ;;
  publish)
    publish_http
    ;;
esac

echo "DONE profile=${PROFILE} mode=${MODE}"
