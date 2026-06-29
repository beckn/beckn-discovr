---
id: "D06"
name: "Postgres primary failover (Discovr)"
group: chaos-infra
duration_min: 30
target: "Postgres primary pod"
chaos_action: "kubectl delete pod (Postgres primary)"
---

## Goal
Same shape as Catalg C06 but for Discovr's Postgres. Verify discover-job and publish-job reconnect cleanly, no data lost, no double-inserts.

## Pre-condition
- Postgres HA: ≥ 1 primary + ≥ 1 standby with sync/semi-sync replication
- Discovr services use the operator's R/W service endpoint (no hardcoded IPs)
- HikariCP `connectionTimeout` < 10 s

## Background load
- JMeter script: `discover-async-success.jmx` + a low-rate push stream from Catalg
- Duration: 30 min, injection at minute 10

## Chaos action (one only)
```
kubectl delete pod -n <discovr-pg-namespace> <discovr-pg-primary-pod> --grace-period=30
```

**Abort / recovery:** operator-driven. If cluster not back to "1 primary + N standbys" within 5 min, STOP and ask user.

## Per-action confirmation contract
- Primary pod name confirmed via operator CR
- Standby lag at moment of action (must be ≤ 1 s WAL)
- Current connection counts on discover-job, publish-job, response-dispatcher
- Current row counts in key tables
- The kill command + abort guidance
- **Loud warning: failover is the highest-risk Phase 2 action**

WAIT.

## Metrics to capture (every 5 s, minute 9 to minute 18)
- Postgres role per replica
- Discover-job + publish-job + dispatcher DB pool active / pending
- Discover ACK rate
- Push receive rate
- on_discover delivery rate
- Connection error logs

## SLOs
- New primary serving within 60 s of kill
- App reconnection complete within 30 s of new primary (90 s total RTO)
- Discover ACK rate dip ≤ 20 % at peak; recovers within 120 s
- Push receive: no message loss (Kafka offsets not committed during PG outage)
- ES doc count and PG row count consistent post-run

## Cleanup verification
- PG cluster: 1 primary + N standbys, lag ≤ 1 s
- All Discovr services healthy on `/actuator/health`
