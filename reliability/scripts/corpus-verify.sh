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
#   DISCOVER_URL     optional — golden T/G/J discover smoke when set
#
# Exit 0 = PASS, 1 = FAIL counts/golden, 2 = usage, 3 = missing tooling/env

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROFILE=""
EXPECT=""
DRY_RUN=0
TOLERANCE="0.001"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --expect) EXPECT="${2:?}"; shift 2 ;;
    --tolerance) TOLERANCE="${2:?}"; shift 2 ;;
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
echo "  profile=$PROFILE target=$TARGET effective_expect=$EFFECTIVE index=$ES_INDEX"

if [[ "$DRY_RUN" -eq 1 ]]; then
  cat <<EOF
DRY-RUN checks that would run:
  - ES GET ${ES_URL:-\$ES_URL}/${ES_INDEX}/_count ≥ $(python3 -c "print(int($EFFECTIVE * (1-$TOLERANCE)))")
  - PG  SELECT COUNT(*) FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%'
  - Golden ES ids: sim-golden-t:sim-golden-t-r0, sim-golden-g:…, sim-golden-j:…
  - Optional DISCOVER_URL smoke with intent-t / intent-g / intent-j
EOF
  exit 0
fi

ES_URL="${ES_URL:?ES_URL required}"
PG_URL="${PG_URL:?PG_URL required}"
command -v psql >/dev/null || { echo "ERROR: psql required" >&2; exit 3; }
command -v curl >/dev/null || { echo "ERROR: curl required" >&2; exit 3; }

ES_COUNT=$(curl -sS "${ES_URL}/${ES_INDEX}/_count" | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))")
PG_COUNT=$(psql "$PG_URL" -tAc \
  "SELECT COUNT(*) FROM item WHERE catalog_id LIKE '${PREFIX}-%' OR catalog_id LIKE 'sim-golden-%';")

MIN=$(python3 -c "print(int($EFFECTIVE * (1.0 - float('$TOLERANCE'))))")

echo "  es_count=$ES_COUNT pg_count=$PG_COUNT min_pass=$MIN"

FAIL=0
if [[ "$ES_COUNT" -lt "$MIN" ]]; then
  echo "FAIL: ES count $ES_COUNT < $MIN"
  FAIL=1
fi
if [[ "$PG_COUNT" -lt "$MIN" ]]; then
  echo "FAIL: PG count $PG_COUNT < $MIN"
  FAIL=1
fi

for gid in sim-golden-t:sim-golden-t-r0 sim-golden-g:sim-golden-g-r0 sim-golden-j:sim-golden-j-r0; do
  code=$(curl -sS -o /dev/null -w "%{http_code}" "${ES_URL}/${ES_INDEX}/_doc/${gid}")
  if [[ "$code" != "200" ]]; then
    echo "FAIL: golden ES doc missing ${gid} (HTTP $code)"
    FAIL=1
  else
    echo "  golden ok $gid"
  fi
done

if [[ -n "${DISCOVER_URL:-}" ]]; then
  INTENT_T="$ROOT/reliability/simulations/fixtures/intents/intent-t.json"
  if [[ -f "$INTENT_T" ]]; then
    code=$(curl -sS -o /tmp/corpus-discover-ack.json -w "%{http_code}" \
      -X POST "$DISCOVER_URL" -H 'Content-Type: application/json' --data-binary @"$INTENT_T")
    echo "  discover golden-T ACK HTTP $code"
    [[ "$code" == "200" || "$code" == "202" ]] || FAIL=1
  fi
fi

if [[ "$FAIL" -eq 0 ]]; then
  echo "PASS corpus-verify profile=${PROFILE}"
  exit 0
fi
echo "FAIL corpus-verify profile=${PROFILE}"
exit 1
