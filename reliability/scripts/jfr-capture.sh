#!/usr/bin/env bash
# Start / dump a short JDK Flight Recorder session against a Discovr JVM.
# Safe defaults: short duration, disk dump only, no heap dump, dry-run first.
#
# Intended for GCP sim / staging pods once a PID or container is available.
# Does NOT require async-profiler.
#
# Usage:
#   ./reliability/scripts/jfr-capture.sh --dry-run --pid 12345 --name discover-ack
#   ./reliability/scripts/jfr-capture.sh --pid 12345 --name discover-ack --duration 60
#   ./reliability/scripts/jfr-capture.sh --pid 12345 --dump-only --recording 1
#
# Output:
#   reliability/reports/<utc>/jfr/<name>.jfr
#
# After capture (on a machine with JDK):
#   jfr print --events jdk.ExecutionSample,jdk.SocketRead,jdk.SocketWrite,jdk.JavaMonitorEnter \
#     reliability/reports/.../jfr/<name>.jfr
#   # or open in JDK Mission Control for flame-like views

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PID=""
NAME="discovr"
DURATION=60
DRY_RUN=0
DUMP_ONLY=0
RECORDING=""
OUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pid) PID="${2:?}"; shift 2 ;;
    --name) NAME="${2:?}"; shift 2 ;;
    --duration) DURATION="${2:?}"; shift 2 ;;
    --recording) RECORDING="${2:?}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --dump-only) DUMP_ONLY=1; shift ;;
    -h|--help)
      sed -n '2,25p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$PID" ]]; then
  echo "ERROR: --pid required (discover/publish/dispatcher JVM)" >&2
  exit 2
fi

if ! command -v jcmd >/dev/null 2>&1; then
  echo "ERROR: jcmd not on PATH (need JDK, not JRE-only)" >&2
  exit 3
fi

UTC="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR:-reliability/reports/${UTC}/jfr}"
mkdir -p "$OUT_DIR"
JFR_FILE="$OUT_DIR/${NAME}.jfr"
SETTINGS="profile"   # JDK built-in profile settings (CPU + allocation + I/O)

echo "target_pid=$PID name=$NAME duration=${DURATION}s out=$JFR_FILE"

if [[ "$DRY_RUN" -eq 1 ]]; then
  if [[ "$DUMP_ONLY" -eq 1 ]]; then
    echo "DRY-RUN would run:"
    echo "  jcmd $PID JFR.dump name=${RECORDING:-1} filename=$JFR_FILE"
  else
    echo "DRY-RUN would run:"
    echo "  jcmd $PID JFR.start name=$NAME settings=$SETTINGS duration=${DURATION}s filename=$JFR_FILE"
    echo "  # wait ${DURATION}s, or: jcmd $PID JFR.dump name=$NAME filename=$JFR_FILE"
  fi
  exit 0
fi

if [[ "$DUMP_ONLY" -eq 1 ]]; then
  jcmd "$PID" JFR.dump "name=${RECORDING:-1}" "filename=$JFR_FILE"
else
  jcmd "$PID" JFR.start "name=$NAME" "settings=$SETTINGS" "duration=${DURATION}s" "filename=$JFR_FILE"
  echo "JFR started for ${DURATION}s → $JFR_FILE"
  echo "Tip: run S1 / load in another terminal while this recording is active."
fi

if [[ -f "$JFR_FILE" ]]; then
  ls -lh "$JFR_FILE"
else
  echo "NOTE: file appears after recording ends (duration=$DURATION). Check: jcmd $PID JFR.check"
fi
