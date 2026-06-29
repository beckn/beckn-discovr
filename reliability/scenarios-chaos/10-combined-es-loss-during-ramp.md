---
id: "D10"
name: "Combined: ES node loss DURING discover ramp"
group: chaos-combined
duration_min: 30
target: "one ES data node + ramping JMeter load"
chaos_action: "kubectl delete pod (ES node) at peak of ramp"
---

## Goal
The worst-case-for-customers scenario: an ES node dies while traffic is ramping up. Verify the cluster handles the dual stress (rebalance + increasing load) without RED, without query failures, and without unbounded latency.

## Pre-condition
- ES cluster ≥ 3 data nodes, replicas ≥ 1
- ES GREEN
- 1M items indexed (or whatever Phase 1 scenario 10 successfully tested)

## Background load
- JMeter script: `discover-throughput-ramp.jmx`
- Ramp: 0 → 100 RPS linearly over 20 min
- Total duration: 30 min
- Injection at minute 14 (during the ramp, around 70 RPS)

## Chaos action (one only)
```
kubectl delete pod -n <es-namespace> <one-es-data-pod> --grace-period=10
```

**Recovery:** StatefulSet self-heal. If pod doesn't return within 5 min, STOP.

## Per-action confirmation contract
- ES cluster state (GREEN, node count, shards)
- Current JMeter RPS (must be at the planned ~70 RPS)
- Current discover p99
- Current ES query p99
- Kill command + abort

## Metrics to capture (every 5 s, full window)
- JMeter RPS actual
- ES cluster health
- ES query p50/p95/p99
- Discover ACK rate
- on_discover delivery rate
- ES heap %
- ES unassigned shards

## SLOs
- ES does NOT go RED at any point
- Discover ACK rate dip ≤ 10 % during the YELLOW window
- Discover p99 stays ≤ 5× steady-state during YELLOW
- on_discover delivery rate dip ≤ 15 %, recovers within 90 s of GREEN
- ES heap stays ≤ 85 % even during ramp + rebalance
- Zero "no node available" errors visible to BAP
- Full GREEN re-attained within 300 s of pod return

## Cleanup verification
- ES GREEN
- All replicas assigned
- on_discover count matches discover count for the window
