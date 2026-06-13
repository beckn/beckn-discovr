#!/usr/bin/env bash
#
# Fire each of the 7 J/G/T query combination scenarios at the local discover
# service. By default uses POST (async) so each returns ACK and the on_discover
# callback is delivered asynchronously by the response-dispatcher.
#
# Override HOST / METHOD with env vars:
#   HOST=http://localhost:8082 ./run-all.sh
#   METHOD=GET ./run-all.sh        # sync — returns full response inline
#
# Authentication: this script does NOT sign requests. Either disable auth in
# application.yml (beckn-auth.required: false) or set AUTH_HEADER env var to
# a pre-computed Signature header.
#
set -euo pipefail

HOST="${HOST:-http://localhost:8082}"
METHOD="${METHOD:-POST}"
AUTH_HEADER="${AUTH_HEADER:-}"
ENDPOINT="$HOST/beckn/discover"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

declare -a CASES=(
  "case-1-jsonpath-only.json:J only       (PSQL JSONPath)"
  "case-2-geo-only.json:G only            (PSQL/ES spatial)"
  "case-3-text-only.json:T only           (ES text — BM25 or KNN)"
  "case-4-jsonpath-geo.json:J+G           (PSQL combined)"
  "case-5-geo-text.json:G+T               (ES — BM25+geo or KNN+geo)"
  "case-6-jsonpath-text.json:J+T          (Chain: ES → IDs → PSQL JSONPath)"
  "case-7-jsonpath-geo-text.json:J+G+T    (Chain: ES+geo → IDs → PSQL J+G)"
)

echo "Endpoint:  $METHOD $ENDPOINT"
echo "Scenarios: ${#CASES[@]}"
echo

PASS=0
FAIL=0
for entry in "${CASES[@]}"; do
  file="${entry%%:*}"
  label="${entry#*:}"
  body_path="$SCRIPT_DIR/$file"

  printf '── %-50s ── ' "$label"

  if [[ ! -f "$body_path" ]]; then
    echo "MISSING $file"
    FAIL=$((FAIL+1))
    continue
  fi

  curl_args=(-sS -o /tmp/discover-response.json -w '%{http_code}'
             -X "$METHOD" "$ENDPOINT"
             -H 'Content-Type: application/json'
             --data @"$body_path")
  [[ -n "$AUTH_HEADER" ]] && curl_args+=(-H "Authorization: $AUTH_HEADER")

  status=$(curl "${curl_args[@]}" || echo "000")

  if [[ "$status" =~ ^2 ]]; then
    echo "HTTP $status  ✓"
    PASS=$((PASS+1))
  else
    echo "HTTP $status  ✗"
    cat /tmp/discover-response.json
    echo
    FAIL=$((FAIL+1))
  fi
done

echo
echo "Result: $PASS passed, $FAIL failed (out of ${#CASES[@]})"
exit $FAIL
