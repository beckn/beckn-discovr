#!/usr/bin/env bash
# Trigger a JMeter run on the configured VM via `gcloud compute ssh`.
# The VM, zone, and user come from cluster.yaml — they are NOT overridable from the command line.
#
# Usage:
#   jmeter-trigger.sh <scenario-id> <jmx-file> [-Jkey=value ...]

set -euo pipefail

CONFIG="reliability/config/cluster.yaml"
SCENARIO_ID="${1:?scenario id required}"
JMX_FILE="${2:?jmx file required}"
shift 2

read_yaml() {
  grep -E "^\s*$1:" "$CONFIG" | head -1 | sed -E "s/.*$1:[[:space:]]*\"([^\"]+)\".*/\1/"
}

VM=$(read_yaml vm_name)
ZONE=$(read_yaml zone)
USER=$(read_yaml user)
SCRIPTS_DIR=$(read_yaml scripts_dir)
RESULTS_DIR=$(read_yaml results_dir)
JMETER_BIN=$(read_yaml jmeter_bin)

if [[ -z "$VM" || "$VM" == "<FILL_JMETER_VM_NAME>" ]]; then
  echo "ERROR: jmeter.vm_name is not set in $CONFIG" >&2
  exit 3
fi

TS=$(date -u +%Y%m%dT%H%M%SZ)
RUN_DIR="$RESULTS_DIR/${SCENARIO_ID}-${TS}"
JTL="$RUN_DIR/results.jtl"
HTML="$RUN_DIR/html"
LOG="$RUN_DIR/jmeter.log"

EXTRA_ARGS=("$@")

REMOTE_CMD="mkdir -p '$RUN_DIR' && '$JMETER_BIN' -n -t '$SCRIPTS_DIR/$JMX_FILE' -l '$JTL' -e -o '$HTML' -j '$LOG' ${EXTRA_ARGS[*]}"

echo "Triggering JMeter on $VM ($ZONE) as $USER"
echo "  scenario: $SCENARIO_ID"
echo "  jmx:      $JMX_FILE"
echo "  run_dir:  $RUN_DIR"

gcloud compute ssh "${USER}@${VM}" --zone "$ZONE" --command "$REMOTE_CMD"

echo "RUN_DIR=$RUN_DIR"
