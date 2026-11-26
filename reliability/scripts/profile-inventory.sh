#!/usr/bin/env bash
# Inventory local / agent-side profiling tooling for Discovr p99 work.
# Safe, read-only. No cluster / GCP mutations.
#
# Usage:
#   ./reliability/scripts/profile-inventory.sh
#   ./reliability/scripts/profile-inventory.sh --json

set -euo pipefail

JSON=0
[[ "${1:-}" == "--json" ]] && JSON=1

have() { command -v "$1" >/dev/null 2>&1; }

check() {
  local name="$1" status="$2" detail="${3:-}"
  if [[ "$JSON" -eq 1 ]]; then
    printf '{"tool":"%s","status":"%s","detail":"%s"}\n' "$name" "$status" "$detail"
  else
    printf '%-22s %-10s %s\n' "$name" "$status" "$detail"
  fi
}

[[ "$JSON" -eq 0 ]] && printf '%-22s %-10s %s\n' "TOOL" "STATUS" "DETAIL" && printf '%s\n' "------------------------------------------------------"

if have java; then
  check "java" "present" "$(java -version 2>&1 | head -1 | tr -d '\r')"
else
  check "java" "missing" "required for JFR / compile"
fi

if have jfr; then
  check "jfr" "present" "$(command -v jfr)"
else
  check "jfr" "missing" "JDK Flight Recorder CLI (OpenJDK jdk.jfr)"
fi

if have jcmd; then
  check "jcmd" "present" "$(command -v jcmd)"
else
  check "jcmd" "missing" "needed for live JFR start/dump"
fi

if have async-profiler || [[ -x /opt/async-profiler/bin/asprof ]]; then
  check "async-profiler" "present" "$(command -v async-profiler 2>/dev/null || echo /opt/async-profiler/bin/asprof)"
else
  check "async-profiler" "missing" "install on GCP sim VM for CPU/alloc flamegraphs"
fi

if have docker; then
  check "docker" "present" "$(docker --version 2>&1 | head -1)"
else
  check "docker" "missing" "local stack / Testcontainers host unavailable"
fi

if have gcloud; then
  check "gcloud" "present" "$(gcloud --version 2>&1 | head -1)"
else
  check "gcloud" "missing" "GCP S1 / ClickStack / JMeter VM path blocked"
fi

if have jmeter; then
  check "jmeter" "present" "$(jmeter -v 2>&1 | head -1)"
else
  check "jmeter" "missing" "use reliability/scripts/jmeter-trigger.sh on JMeter VM"
fi

# Repo-side observability anchors
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
for path in \
  "jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/DiscoveryMetrics.java" \
  "reliability/scripts/clickstack-query.sh" \
  "reliability/scripts/jmeter-trigger.sh" \
  "docs/reviews/perf-review-unified-2026-04-30.md" \
  "config/es-index-template.json"
do
  if [[ -f "$ROOT/$path" ]]; then
    check "repo:$path" "present" ""
  else
    check "repo:$path" "missing" ""
  fi
done
