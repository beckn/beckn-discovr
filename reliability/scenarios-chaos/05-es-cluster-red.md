---
id: "D05"
name: "Elasticsearch cluster RED — multi-node loss"
group: chaos-infra
duration_min: 30
target: "ES data nodes (kill enough to make at least one primary shard unavailable)"
chaos_action: "kubectl delete pod (multiple ES nodes — REQUIRES EXTRA CONFIRMATION)"
---

## Goal
Force the ES cluster into RED (some primary shards unavailable). The Discovr application must NOT silently swallow this — discover queries must NACK or degrade gracefully with a clear error; writes must accumulate in Kafka (consumer lag grows, but no message loss); when ES recovers, the backlog drains.

## **DESTRUCTIVE — extra confirmation required**

This is the most destructive Phase 2 scenario. The agent MUST:
1. Show a special "DESTRUCTIVE" warning banner in the confirmation prompt
2. Re-confirm with the user that the target cluster is the staging cluster (re-print `cluster.context`)
3. Refuse to run if the run is on Friday after 16:00 UTC or any time the user marks as "out of hours" (config: `chaos.out_of_hours_windows`)
4. Refuse to run if any earlier Phase 2 scenario in this run FAILED

## Pre-condition
- ES cluster has ≥ 3 data nodes, replicas == 1 (so killing 2 nodes for the same shard exposes the gap)
- A specific test index identified — the chaos will target shards on this index, not production-traffic indices
- Cluster GREEN

## Background load
- JMeter script: `discover-throughput-ramp.jmx` at low steady rate (10 RPS — we are stress-testing failure modes, not throughput)
- Duration: 30 min (chaos at minute 8, recovery starts minute 18)

## Chaos action (TWO actions, separate confirmations)

### Action 1: Kill first ES data node
```
kubectl delete pod -n <es-namespace> <es-data-pod-A> --grace-period=10
```

WAIT for cluster to go YELLOW. Verify (don't proceed unless YELLOW). Then ask for the next confirmation.

### Action 2: Kill a second ES data node (the one holding the replica of A's primaries)
```
kubectl delete pod -n <es-namespace> <es-data-pod-B> --grace-period=10
```

The agent identifies pod B by querying `_cat/shards` for shards whose primary was on A and whose replica is on a still-running node. Print this reasoning to the user before Action 2.

**Recovery (passive):** both pods self-heal via StatefulSet. If they do not return within 5 min, STOP.

## Per-action confirmation contract (BOTH actions)
- Cluster health, node count, current unassigned shard count
- Which test-index shards are on the target pod
- The kill command + the abort guidance
- For Action 2: explicit "you are about to take the cluster RED" warning

## Metrics to capture (every 5 s, minute 7 to minute 25)
- ES cluster health (GREEN/YELLOW/RED)
- Unassigned shard count
- discover-job error rate by category (ES connection, ES timeout, no shard available)
- Discover NACK rate
- Kafka publish-in lag (Discovr publish-job — should grow since ES is unavailable)
- on_discover callback delivery rate

## SLOs
- During RED window:
  - Discover MUST NACK queries that hit unavailable shards with a clear error code (e.g. `STORAGE_UNAVAILABLE`) — silent timeouts are FAIL
  - Discovr publish-job MUST NOT commit Kafka offsets for messages whose ES indexing failed — verified by post-run replay
  - The system MUST NOT pin its thread pools waiting on ES
- After RED → GREEN:
  - All deferred publishes drain from Kafka backlog within 300 s
  - ES doc count == publish count (no lost writes)
  - Discover returns to baseline within 120 s of GREEN

## Cleanup verification
- ES GREEN, all shards assigned
- Kafka publish-in topic at baseline lag
- Sample 5 catalogs from RED window: confirmed in ES with correct content
