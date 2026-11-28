#!/usr/bin/env bash
# Verify indexed corpus counts + golden discoverability for a ladder profile.
#
# Usage:
#   corpus-verify.sh --profile 1M
#   corpus-verify.sh --profile local-smoke --expect 1000
#   corpus-verify.sh --profile 1M --dry-run
#
# Env:
#   ES_URL, PG_URL   required for live verify (unless --dry-run)
#   ES_ALIAS         discover alias to count through (default: beckn-catalog)
#   DISCOVER_URL     optional — golden T/G/J discover ACK smoke when set
#
# Exit 0 = PASS, 1 = FAIL counts/golden, 2 = usage, 3 = missing tooling/env

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROFILE=""
EXPECT=""
DRY_RUN=0
TOLERANCE="0.001"
ES_ALIAS="${ES_ALIAS:-beckn-catalog}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --expect) EXPECT="${2:?}"; shift 2 ;;
    --tolerance) TOLERANCE="${2:?}"; shift 2 ;;
    --es-alias) ES_ALIAS="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$PROFILE" ]] || { echo "ERROR: --profile required" >&2; exit 2; }

GEN="$ROOT/reliability/scripts/corpus-generate.py"
SUMMARY=$(python3 "$GEN" --profile "$PROFILE" --format summary)
TARGET=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['resources'])")
ES_INDEX=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['es_index'])")
PREFIX=$(echo "$SUMMARY" | python3 -c "import sys,json; print(json.load(sys.stdin)['catalog_id_prefix'])")
EFFECTIVE="${EXPECT:-$TARGET}"

echo "=== corpus-verify ==="
echo "  profile=$PROFILE target=$TARGET effective_expect=$EFFECTIVE index=$ES_INDEX alias=$ES_ALIAS"

if [[ "$DRY_RUN" -eq 1 ]]; then
  cat <<EOF
DRY-RUN checks that would run:
  - ES GET \${ES_URL}/${ES_ALIAS}/_count with filter catalog_id prefix ≥ $(python3 -c "print(int($EFFECTIVE * (1-$TOLERANCE)))")
  - Confirm alias ${ES_ALIAS} includes index ${ES_INDEX}
  - PG  SELECT COUNT(*) FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%'
  - Golden ES docs via alias: sim-golden-t:…, sim-golden-g:…, sim-golden-j:…
  - Golden ES _search probes (T term / G geo) against alias
  - Optional DISCOVER_URL smoke with intent-t / intent-g / intent-j
EOF
  exit 0
fi

ES_URL="${ES_URL:?ES_URL required}"
PG_URL="${PG_URL:?PG_URL required}"
command -v psql >/dev/null || { echo "ERROR: psql required" >&2; exit 3; }
command -v curl >/dev/null || { echo "ERROR: curl required" >&2; exit 3; }

# Alias must point at the sim index
ALIAS_OK=$(curl -sS "${ES_URL}/_alias/${ES_ALIAS}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('1' if '${ES_INDEX}' in d else '0')" 2>/dev/null || echo 0)
if [[ "$ALIAS_OK" != "1" ]]; then
  echo "FAIL: alias ${ES_ALIAS} does not include index ${ES_INDEX} — discover cannot see this corpus"
  echo "  hint: corpus-seed.sh bulk attaches the alias; re-run seed or: POST /_aliases add"
  FAIL_ALIAS=1
else
  echo "  alias ok ${ES_ALIAS} → ${ES_INDEX}"
  FAIL_ALIAS=0
fi

# Count through the alias (what discover searches), filtered to this rung when possible
ES_COUNT=$(curl -sS -X POST "${ES_URL}/${ES_ALIAS}/_count" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":{\"bool\":{\"should\":[
        {\"prefix\":{\"catalog_id\":\"${PREFIX}-\"}},
        {\"prefix\":{\"catalog_id\":\"sim-golden-\"}}
      ],\"minimum_should_match\":1}}}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))")

PG_COUNT=$(psql "$PG_URL" -tAc \
  "SELECT COUNT(*) FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';")

MIN=$(python3 -c "print(int($EFFECTIVE * (1.0 - float('$TOLERANCE'))))")

echo "  es_alias_count=$ES_COUNT pg_count=$PG_COUNT min_pass=$MIN"

FAIL=0
[[ "$FAIL_ALIAS" -eq 0 ]] || FAIL=1
if [[ "$ES_COUNT" -lt "$MIN" ]]; then
  echo "FAIL: ES alias count $ES_COUNT < $MIN"
  FAIL=1
fi
if [[ "$PG_COUNT" -lt "$MIN" ]]; then
  echo "FAIL: PG count $PG_COUNT < $MIN"
  FAIL=1
fi

for gid in sim-golden-t:sim-golden-t-r0 sim-golden-g:sim-golden-g-r0 sim-golden-j:sim-golden-j-r0; do
  code=$(curl -sS -o /dev/null -w "%{http_code}" "${ES_URL}/${ES_ALIAS}/_doc/${gid}")
  if [[ "$code" != "200" ]]; then
    # Prefer get via index if alias routing differs
    code2=$(curl -sS -o /dev/null -w "%{http_code}" "${ES_URL}/${ES_INDEX}/_doc/${gid}")
    if [[ "$code2" != "200" ]]; then
      echo "FAIL: golden ES doc missing ${gid} (alias HTTP $code index HTTP $code2)"
      FAIL=1
    else
      echo "  golden on index but not via alias get ${gid} (index ok) — search probe next"
    fi
  else
    echo "  golden ok $gid"
  fi
done

# Query-path golden probes against the alias (not raw index)
es_hit_total() {
  python3 -c "import sys,json; r=json.load(sys.stdin); t=r.get('hits',{}).get('total',0); print(t if isinstance(t,int) else t.get('value',0))"
}

T_HITS=$(curl -sS -X POST "${ES_URL}/${ES_ALIAS}/_search" \
  -H 'Content-Type: application/json' \
  -d '{"size":1,"track_total_hits":true,"query":{"match":{"full_text_blob":"instant coffee powder"}}}' \
  | es_hit_total)

G_HITS=$(curl -sS -X POST "${ES_URL}/${ES_ALIAS}/_search" \
  -H 'Content-Type: application/json' \
  -d '{"size":1,"track_total_hits":true,"query":{"bool":{"filter":[{"geo_shape":{"loc_catalogs_resources_availableAt.geo":{"shape":{"type":"Point","coordinates":[77.5946,12.9716]},"relation":"intersects"}}}]}}}' \
  | es_hit_total)

echo "  golden_search T_hits=$T_HITS G_hits=$G_HITS"
if [[ "${T_HITS:-0}" -lt 1 ]]; then
  echo "FAIL: BM25 golden probe against alias returned 0 hits"
  FAIL=1
fi
if [[ "${G_HITS:-0}" -lt 1 ]]; then
  echo "FAIL: geo_shape golden probe against alias returned 0 hits (check loc_catalogs_resources_availableAt.geo)"
  FAIL=1
fi

# PG path sanity for golden-g (wildcard path publish job uses)
PG_GEO=$(psql "$PG_URL" -tAc \
  "SELECT COUNT(*) FROM item_location_collection WHERE catalog_id = 'sim-golden-g' AND path = '\$.catalogs[*].resources[*].availableAt[*].geo';")
# Note: bash double-quoted "\$" → SQL string starting with $
echo "  pg_golden_g_path_rows=$PG_GEO"
if [[ "${PG_GEO:-0}" -lt 1 ]]; then
  echo "FAIL: golden-g location path missing (expected \$.catalogs[*].resources[*].availableAt[*].geo)"
  FAIL=1
fi

if [[ -n "${DISCOVER_URL:-}" ]]; then
  for intent in intent-t.json intent-g.json intent-j.json; do
    f="$ROOT/reliability/simulations/fixtures/intents/$intent"
    [[ -f "$f" ]] || continue
    code=$(curl -sS -o /tmp/corpus-discover-ack.json -w "%{http_code}" \
      -X POST "$DISCOVER_URL" -H 'Content-Type: application/json' --data-binary @"$f")
    echo "  discover ${intent} ACK HTTP $code"
    [[ "$code" == "200" || "$code" == "202" ]] || FAIL=1
  done
fi

if [[ "$FAIL" -eq 0 ]]; then
  echo "PASS corpus-verify profile=${PROFILE}"
  exit 0
fi
echo "FAIL corpus-verify profile=${PROFILE}"
exit 1
