---
id: "D11"
name: "Kubernetes node failure (drain simulation) — Discovr"
group: chaos-infra
duration_min: 30
target: "one GKE node hosting Discovr workloads"
chaos_action: "kubectl drain (cordon + evict)"
---

## Goal
Same shape as Catalg C11 but for Discovr. Critical extra check: if the target node hosts an ES data pod, the cluster behavior under drain should match D04 (ES node loss) — verify the StatefulSet handles the eviction gracefully.

## Pre-condition
- ≥ 3 nodes in node pool
- `topologySpreadConstraints` or `podAntiAffinity` configured for Discovr deployments
- ES StatefulSet has anti-affinity so two ES pods are not on the same node
- PDB configured for Discovr deployments
- ES cluster GREEN

## Background load
- JMeter script: mixed `discover-async-success.jmx` + low push from Catalg
- 25 concurrent users, 30 min
- Drain at minute 10, uncordon at minute 22

## Chaos actions (TWO actions — separate confirmations)

### Action 1: Cordon + drain
```
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data --grace-period=60 --timeout=300s
```

**Abort:**
```
kubectl uncordon <node-name>
```

### Action 2: Uncordon
```
kubectl uncordon <node-name>
```

## Per-action confirmation contract (BOTH actions)

For Action 1:
- Target node name + zone + region
- Pods on the node (filtered to Discovr namespaces + ES namespace if applicable)
- For each deployment hit: total replicas, replicas on target node, PDB
- For ES specifically: number of shards (primary + replica) hosted on the target node — print this loudly
- Other nodes with capacity
- The cordon + drain commands
- The uncordon abort

If an ES data pod is on the target node, treat this scenario as ALSO testing D04 semantics — recovery budget must include ES re-balance.

Refuse if any deployment has all replicas on the target node.

## Metrics to capture (every 5 s, full window)
- Pod count per Discovr deployment, by node
- Pods Pending / Terminating
- ES cluster health (if ES pod evicted)
- ES unassigned shards (if ES pod evicted)
- Discover ACK rate + p99
- on_discover delivery rate
- Kafka consumer lag

## SLOs
- Zero pods stuck Pending > 60 s
- Discover ACK rate dip ≤ 10 %, recovers within 60 s
- on_discover delivery rate dip ≤ 15 %, recovers within 90 s
- ES (if affected): YELLOW only, never RED; GREEN re-attained within 300 s of node return
- Lag spike bounded, recovers within 120 s
- Zero DLT attributable to drain
- Zero documents lost in ES (post-run doc count check)

## Cleanup verification
- Node uncordoned, schedulable
- All Discovr deployments at desired replicas
- ES GREEN (if ES pod was evicted, it should be back on the node or another)
- on_discover count == discover count for the window
