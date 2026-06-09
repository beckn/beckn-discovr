#!/usr/bin/env bash
# Hard-fail if kubectl current-context does not match cluster.yaml.
# The reliability agent calls this before any other action.

set -euo pipefail

CONFIG="${1:-reliability/config/cluster.yaml}"

if [[ ! -f "$CONFIG" ]]; then
  echo "ERROR: config file not found: $CONFIG" >&2
  exit 2
fi

EXPECTED_CTX=$(grep -E '^\s*context:' "$CONFIG" | head -1 | sed -E 's/.*context:[[:space:]]*"([^"]+)".*/\1/')

if [[ -z "$EXPECTED_CTX" || "$EXPECTED_CTX" == "<FILL_KUBECTL_CONTEXT>" ]]; then
  echo "ERROR: cluster.context is not set in $CONFIG" >&2
  exit 3
fi

CURRENT_CTX=$(kubectl config current-context 2>/dev/null || echo "")

if [[ "$CURRENT_CTX" != "$EXPECTED_CTX" ]]; then
  echo "ERROR: kubectl context mismatch" >&2
  echo "  expected: $EXPECTED_CTX" >&2
  echo "  current:  $CURRENT_CTX" >&2
  echo "Run: kubectl config use-context $EXPECTED_CTX" >&2
  exit 4
fi

echo "OK: kubectl context = $EXPECTED_CTX"
