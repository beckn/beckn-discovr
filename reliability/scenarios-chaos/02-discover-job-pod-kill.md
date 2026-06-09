---
id: "D02"
name: "Discover-job pod kill mid-query"
group: chaos-podkill
duration_min: 25
target: "catalog-discover-job deployment"
chaos_action: "kubectl delete pod"
---

## Goal
`/discover` ACKs synchronously, then processes the query asynchronously and POSTs `on_discover` back via response-dispatcher. A discover-job pod kill must not lose the in-flight query (Kafka offset uncommitted), must not double-emit, and must not pin the dispatcher.

## Pre-condition
- ≥ 2 discover-job replicas
- Steady-state ES query p99 within SLO
- Discover work topic lag ≈ 0
- `response-dispatcher` healthy

## Background load
- JMeter script: `discover-async-success.jmx` (synchronous ACK + async callback receive)
- 30 concurrent users, 25 min
- Injection at minute 10

## Chaos action (one only)
```
kubectl delete pod -n <discover-namespace> <one-discover-job-pod> --grace-period=10
```

**Abort / recovery:**
```
kubectl rollout status deployment/catalog-discover-job -n <discover-namespace> --timeout=180s
```

## Per-action confirmation contract
- Target pod + node + uptime
- In-flight queries (estimated)
- Current ACK rate (should remain stable — ACK comes from the API, not from this pod)
- on_discover callback success rate
- Kill command + abort command

WAIT.

## Metrics to capture (every 5 s, minute 9 to minute 18)
- Discover ACK rate (should be unaffected — ACK is synchronous at API layer)
- Discover work topic consumer lag
- on_discover callback delivery rate
- on_discover callback duplicate rate (verified at JMeter listener)
- Discover ERROR log count
- ES query rate (should dip during pod gap, then recover)
- response-dispatcher queue depth

## SLOs (recovery budget = 120 s)
- ACK rate unaffected (no dip > 1 %)
- on_discover deliveries lag spike ≤ 60 s; all in-flight queries eventually produce on_discover within 90 s of pod recovery
- Zero duplicate on_discover at JMeter listener (verified by messageId)
- Zero "query failed" errors visible to the BAP (i.e. errors that don't trigger a fallback or retry)
- No CrashLoopBackOff

## Cleanup verification
- All discover-job pods Ready
- on_discover count == discover count from JMeter window (within budget time)
- No leftover in-flight messages in discover work topic
