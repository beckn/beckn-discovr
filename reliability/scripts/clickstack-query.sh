#!/usr/bin/env bash
# Run a read-only ClickStack query (ClickHouse SQL) and print the result.
#
# Self-hosted HyperDX (ClickStack) does not expose the SaaS /api/v1/query endpoint,
# so this script bypasses HyperDX entirely and execs `clickhouse-client` inside the
# ClickHouse pod. Inside the pod, local connections need no credentials — which
# also means no API key, no port-forward, no env-var dance.
#
# Usage:
#   clickstack-query.sh "SELECT 1"
#   clickstack-query.sh @path/to/query.sql
#   clickstack-query.sh --format JSON "SELECT count() FROM default.otel_metrics_gauge"
#
# Requires:
#   - KUBECONFIG pointing at the reliability cluster
#   - kubectl access to the monitoring namespace
#   - clickhouse-client present in the ClickHouse pod (it is, by default)

set -euo pipefail

CONFIG="reliability/config/cluster.yaml"
CH_NAMESPACE="monitoring"
CH_LABEL="app=clickhouse"
FORMAT="${FORMAT:-PrettyCompact}"   # override via env or first arg

# Optional --format flag
if [[ "${1:-}" == "--format" ]]; then
    FORMAT="$2"
    shift 2
fi

QUERY="${1:?query (or @file) required}"

if [[ ! -f "$CONFIG" ]]; then
    echo "ERROR: $CONFIG not found (run from beckn-discovr repo root)" >&2
    exit 3
fi

if [[ "$QUERY" == @* ]]; then
    if [[ ! -f "${QUERY#@}" ]]; then
        echo "ERROR: query file ${QUERY#@} not found" >&2
        exit 3
    fi
    QUERY_BODY=$(cat "${QUERY#@}")
else
    QUERY_BODY="$QUERY"
fi

CH_POD=$(kubectl -n "$CH_NAMESPACE" get pod -l "$CH_LABEL" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [[ -z "$CH_POD" ]]; then
    echo "ERROR: no clickhouse pod found in $CH_NAMESPACE (label $CH_LABEL)" >&2
    exit 4
fi

kubectl -n "$CH_NAMESPACE" exec "$CH_POD" -- \
    clickhouse-client --format "$FORMAT" --query "$QUERY_BODY"
