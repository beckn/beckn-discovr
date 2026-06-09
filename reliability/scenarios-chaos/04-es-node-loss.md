---
id: "D04"
name: "Elasticsearch node loss (1 of N)"
group: chaos-infra
duration_min: 35
target: "one Elasticsearch data node"
chaos_action: "kubectl delete pod (ES data node)"
---

## Goal
Lose one ES data node. Cluster must go from GREEN to YELLOW (degraded but functional) and then back to GREEN once the node returns. Queries must continue serving (from replica shards). Writes must continue (with bulk retries if a shard is briefly unavailable).

## Pre-condition
- ES cluster has ≥ 3 data nodes
- All indices have `number_of_replicas >= 1`
- Cluster GREEN at steady state
- All shards assigned

## Background load
- JMeter script: `discover-throughput-ramp.jmx` running at steady-state 30 RPS
- 30 concurrent users, 35 min
- Injection at minute 12, node stays gone for ~8 min, then recovers

## Chaos action (one only — StatefulSet self-heals)
```
kubectl delete pod -n <es-namespace> <es-data-pod-N> --grace-period=10
```

**Abort / recovery (passive):**
```
kubectl rollout status statefulset/<es-data-sts> -n <es-namespace> --timeout=600s
# manual: if pod does not return within 5 min, STOP and ask user
```

## Per-action confirmation contract
- Target ES pod name + ordinal
- Cluster health (GREEN), node count, shard count, unassigned shards (must be 0)
- Current ES query latency p99
- Current ES bulk indexing rate
- Kill command + abort guidance

## Metrics to capture (every 10 s, minute 11 to minute 25)
- ES cluster health status (GREEN/YELLOW/RED)
- Unassigned shard count
- Initializing shard count
- ES query p50/p95/p99
- ES bulk index success rate
- ES bulk index reject rate
- Discover ACK rate
- on_discover delivery rate
- discover-job ES query error rate

## SLOs (recovery budgets: query 30 s, write 60 s, GREEN re-attain 300 s)
- Cluster status transitions: GREEN → YELLOW within 30 s of kill → YELLOW for the gap → GREEN within 300 s of pod return
- Cluster status NEVER goes RED (replica factor must protect this)
- Discover ACK rate dip ≤ 5 %
- on_discover callback rate dip ≤ 10 %
- ES query p99 may rise during YELLOW but ≤ 3× steady-state
- ES bulk reject rate ≤ 1 % sustained during YELLOW
- Zero documents lost — `count(*) by _id` post-run matches publish count from JMeter
- Zero "no node available" errors that surface to the BAP

## Cleanup verification
- ES GREEN
- All shards assigned, replicas == configured count
- JMeter: every discover_id from the window received an on_discover
- ES doc count matches expected
