---
id: "D07"
name: "Kafka broker loss (Discovr)"
group: chaos-infra
duration_min: 35
target: "one Kafka broker"
chaos_action: "kubectl delete pod (broker)"
---

## Goal
Same shape as Catalg C05 but verified against Discovr-specific topics: push-in, discover work, dispatch, response-dispatcher's outbound, and any internal Discovr topics.

## Pre-condition
- Kafka cluster: ≥ 3 brokers, replication factor ≥ 3, min.insync.replicas ≥ 2 on all Discovr topics
- 0 under-replicated partitions at steady state

## Background load
- JMeter script: `discover-async-success.jmx`
- 25 concurrent users, 35 min
- Injection at minute 12, recovery monitored to minute 35

## Chaos action (one only)
```
kubectl delete pod -n <kafka-namespace> <broker-pod-N> --grace-period=10
```

**Recovery:** StatefulSet self-heals. If pod doesn't return within 5 min, STOP.

## Per-action confirmation contract
- Target broker pod + ordinal
- ISR set for representative Discovr topics
- Current lag for all Discovr consumer groups
- Producer send-success rate
- Kill command + abort

## Metrics to capture (every 10 s)
- Under-replicated partition count
- Offline partition count (must stay 0)
- Producer error rate (Catalg pushing, Discovr internal producers)
- Consumer lag per Discovr group
- Push receive ACK rate
- Discover ACK rate
- on_discover delivery rate

## SLOs
- Producer error rate spike ≤ 1 %
- Discover ACK rate dip ≤ 5 %
- All Discovr consumer lag spikes ≤ 5000 msgs, returns to steady-state + 50 within 90 s
- Zero offline partitions
- ISR restored within 300 s of broker rejoin
- Zero entries in any Discovr DLT attributable to broker loss

## Cleanup verification
- Under-replicated partitions == 0
- All consumer groups at baseline lag
- on_discover count matches discover count for the window
