---
id: "D03"
name: "Response-dispatcher pod kill mid-callback"
group: chaos-podkill
duration_min: 25
target: "response-dispatcher deployment"
chaos_action: "kubectl delete pod"
---

## Goal
`response-dispatcher` POSTs `on_discover` callbacks. A pod kill mid-POST must not lose the callback message (Kafka redelivery), must not deliver the same `messageId` twice to the same BAP, and must not exhaust the HTTP connection pool on the survivor.

## Pre-condition
- ≥ 2 dispatcher replicas
- Steady-state lag on dispatch topic ≈ 0
- JMeter listener is the receiving BAP — records every on_discover by `messageId`

## Background load
- JMeter script: `discover-async-success.jmx`
- 30 concurrent users, 25 min
- Injection at minute 10

## Chaos action (one only)
```
kubectl delete pod -n <dispatcher-namespace> <one-dispatcher-pod> --grace-period=10
```

**Abort / recovery:**
```
kubectl rollout status deployment/response-dispatcher -n <dispatcher-namespace> --timeout=180s
```

## Per-action confirmation contract
- Target pod + node + uptime
- Current dispatch topic lag
- Current HTTP POST in-flight count (active connections from RestTemplate pool metric)
- on_discover callback success rate
- Kill command + abort command

WAIT.

## Metrics to capture (every 5 s, minute 9 to minute 18)
- Dispatch topic consumer lag
- HTTP POST success rate
- HTTP POST 4xx/5xx rate
- HTTP connection pool active + idle (on survivor)
- JMeter listener: received `messageId` count + duplicate detection
- Dispatcher DLT count

## SLOs (recovery budget = 120 s)
- Lag returns to ≤ steady-state + 5 within 120 s
- Zero duplicate `messageId` received at JMeter listener (this is the headline assertion)
- HTTP POST success rate dips briefly, recovers to ≥ 99.9 % within 60 s
- Survivor's HTTP connection pool stays bounded (active ≤ 80 % of capacity)
- No DLT growth attributable to the kill
- No CrashLoopBackOff

## Cleanup verification
- All dispatcher pods Ready
- JMeter listener: every messageId from discover window received exactly once
- Dispatch topic at baseline lag
