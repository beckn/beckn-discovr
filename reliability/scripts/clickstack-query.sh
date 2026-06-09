#!/usr/bin/env bash
# Run a read-only ClickStack query and print the JSON response.

set -euo pipefail

CONFIG="reliability/config/cluster.yaml"
QUERY="${1:?query (or @file) required}"

BASE_URL=$(grep -E '^\s*base_url:' "$CONFIG" | head -1 | sed -E 's/.*base_url:[[:space:]]*"([^"]+)".*/\1/')
API_KEY_ENV=$(grep -E '^\s*api_key_env:' "$CONFIG" | head -1 | sed -E 's/.*api_key_env:[[:space:]]*"([^"]+)".*/\1/')

if [[ -z "$BASE_URL" || "$BASE_URL" == "<FILL_CLICKSTACK_BASE_URL>" ]]; then
  echo "ERROR: clickstack.base_url is not set in $CONFIG" >&2
  exit 3
fi

API_KEY="${!API_KEY_ENV:-}"
if [[ -z "$API_KEY" ]]; then
  echo "ERROR: env var $API_KEY_ENV is not set" >&2
  exit 4
fi

if [[ "$QUERY" == @* ]]; then
  QUERY_BODY=$(cat "${QUERY#@}")
else
  QUERY_BODY="$QUERY"
fi

curl -sS -X POST "$BASE_URL/api/v1/query" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  --data-raw "$(jq -n --arg q "$QUERY_BODY" '{query:$q}')"
