---
id: "D09"
name: "Rolling restart of each Discovr job under load"
group: chaos-rolling
duration_min: 40
target: "each Discovr deployment, sequentially"
chaos_action: "kubectl rollout restart"
---

## Goal
Rolling restart must be a non-event for queries (discover ACK), for pushes (Discovr publish-job), and for callbacks (response-dispatcher).

## Pre-condition
- All deployments ≥ 2 replicas, healthy
- ES GREEN, PG healthy
- Steady-state lag ≈ 0 on all consumer groups

## Background load
- Mixed: `discover-async-success.jmx` + low-rate push from Catalg
- Duration: 40 min (3 rollouts × ~10 min apart)

## Chaos actions (THREE actions, separate confirmations)

### Action 1
```
kubectl rollout restart deployment/catalog-publish-job -n <discovr-publish-namespace>
```

### Action 2 (after Action 1 fully done + 2 min steady)
```
kubectl rollout restart deployment/catalog-discover-job -n <discover-namespace>
```

### Action 3 (after Action 2 fully done + 2 min steady)
```
kubectl rollout restart deployment/response-dispatcher -n <dispatcher-namespace>
```

**Abort for each:**
```
kubectl rollout undo deployment/<name> -n <ns>
kubectl rollout status deployment/<name> -n <ns> --timeout=300s
```

## Per-action confirmation contract (each)
- Deployment name + current replicas + image
- Current lag (for jobs) or ACK rate (for API-facing)
- Time since last steady-state confirmation
- Rollout command + undo command

## Metrics to capture (every 10 s for full 40 min)
- Pod ready count per deployment
- Kafka consumer lag per Discovr group
- Discover ACK rate + p99
- on_discover delivery rate
- ES query rate
- ES bulk index rate

## SLOs per rollout
- **publish-job**: lag spike ≤ 2× steady-state throughput × 30 s; ES doc count grows continuously (no stall)
- **discover-job**: ACK rate unaffected (synchronous ACK from API layer, not from this pod); on_discover delivery lag spike ≤ 60 s
- **response-dispatcher**: zero duplicate on_discover at JMeter listener; HTTP POST rate dip ≤ 1 min
- All rollouts: zero new DLT entries attributable to the rollout

## Cleanup verification
- All deployments at desired replicas, all on new ReplicaSet
- Lag at baseline
- on_discover count == discover count for the JMeter window
