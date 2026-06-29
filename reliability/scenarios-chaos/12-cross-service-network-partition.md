---
id: "D12"
name: "Network partition: Catalg → Discovr push delivery"
group: chaos-network
duration_min: 30
target: "egress from Catalg delivery-job to Discovr publish endpoint (or the Kafka broker between them, if push goes via Kafka)"
chaos_action: "chaos-mesh NetworkChaos (partition)"
---

## Goal
The link from Catalg to Discovr drops (deployment in a different VPC, transit gateway issue, Kafka broker partition). Verify Catalg's delivery-job retries correctly, no messages are lost, and once the partition heals, the backlog drains within budget.

## Pre-condition
- chaos-mesh NetworkChaos available (`networkchaos.chaos-mesh.org` CRD installed)
- Both Catalg (`delivery-job` source) and Discovr (`publish-job` target) running on the same cluster (this scenario assumes single-cluster — adapt the selector if they're cross-cluster)
- Steady-state push delivery success rate ≥ 99.9 %

## Background load
- JMeter script: `publish-baseline.jmx` running against Catalg
- 25 concurrent users, 30 min
- Partition active minute 10 → minute 20

## Chaos action (one only — CRD apply + delete)

```
cat <<EOF | kubectl apply -f -
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: catalg-discovr-partition-<run-id>
  namespace: <catalg-delivery-namespace>
spec:
  action: partition
  mode: all
  selector:
    namespaces: [<catalg-delivery-namespace>]
    labelSelectors:
      app: catalog-delivery-job
  direction: to
  target:
    mode: all
    selector:
      namespaces: [<discovr-publish-namespace>]
      labelSelectors:
        app: catalog-publish-job
  duration: 10m
EOF
```

**Cleanup (ALWAYS runs):**
```
kubectl delete networkchaos catalg-discovr-partition-<run-id> -n <catalg-delivery-namespace>
```

The cleanup is registered as a deferred action and runs even if the scenario fails midway.

## Per-action confirmation contract
- chaos-mesh CRD presence verified
- Source pod selector + target pod selector
- Current Catalg → Discovr delivery success rate
- Current Discovr publish-job consumer lag (will grow during partition)
- The full CRD YAML
- The cleanup command
- Maximum duration

WAIT.

## Metrics to capture (every 5 s, minute 9 to minute 22)
- Catalg delivery-job HTTP POST success rate to Discovr endpoint
- Catalg delivery-job retry count
- Catalg delivery `catalg.delivery.dlt.queue` count
- Discovr publish-job consumer lag (will grow during partition)
- ES bulk index rate at Discovr (will dip to zero during partition)
- Catalg API ACK rate (should be UNAFFECTED — Catalg's local Kafka still works)
- Catalg indexer pipeline rate (should be UNAFFECTED)

## SLOs (during partition)
- Catalg API ACK rate unaffected (publish layer doesn't depend on Discovr)
- Catalg indexer pipeline unaffected
- Catalg delivery retries respect backoff policy — verified by retry rate ramp-up not being a tight loop
- Catalg delivery DLT growth attributable to partition: per scenario tolerance — most messages should ride through with retries
- Discovr publish-job lag grows (expected) but consumer is alive (no rebalance, no crash)

## SLOs (after partition heals — minute 20 onwards)
- Delivery success rate returns to ≥ 99.9 % within 60 s
- Discovr publish-job lag drains within `5 × duration-of-partition` or 600 s, whichever is smaller
- Zero duplicate documents in Discovr ES post-run
- Zero permanently lost messages — every catalog Catalg ACKed during the window appears in Discovr ES post-recovery

## Cleanup verification
- NetworkChaos CRD deleted
- Catalg delivery → Discovr success rate at baseline
- Discovr publish-job lag at baseline
- ES doc count matches expected
