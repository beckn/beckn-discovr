---
id: "D08"
name: "External enrichment API blackhole"
group: chaos-degradation
duration_min: 25
target: "external_enrichment.mock_url"
chaos_action: "switch mock_url to a TCP-accept-no-response endpoint"
---

## Goal
The external enrichment API stops responding entirely (TCP connects succeed, no HTTP response, no FIN). The dispatcher / discover-job must time out cleanly, must not exhaust the connection pool, must trigger circuit breaker if configured, and on_discover must either degrade gracefully (fallback) or NACK cleanly — never hang indefinitely.

## Pre-condition
- `external_enrichment.mock_url` is configurable per phase (header / query param) to switch between healthy and blackhole modes — no kubectl mutation needed
- A blackhole mock endpoint exists (or chaos-mesh NetworkChaos can simulate it — see below)
- Discovr has `external.timeout_ms` configured (and the agent reads it from config)
- Circuit breaker or bulkhead configured (Resilience4j or similar)

## Background load
- JMeter script: `discover-async-success.jmx`
- 25 concurrent users, 25 min
- Blackhole active from minute 8 to minute 18, then external recovers (mode switched back)

## Approach options (pick whichever is available)

### Option A — JMeter parameter (preferred, fully reversible, no cluster mutation)
JMeter sends a header/query parameter to discover queries indicating which external behavior the mock should exhibit. The mock is a permanent fixture; JMeter just toggles which mode it should reply with. No chaos-mesh, no kubectl needed.

### Option B — chaos-mesh NetworkChaos (only if Option A not possible)
```
cat <<EOF | kubectl apply -f -
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: discovr-external-blackhole-<run-id>
  namespace: <discover-namespace>
spec:
  action: loss
  mode: all
  selector:
    namespaces: [<discover-namespace>]
    labelSelectors:
      app: catalog-discover-job
  loss:
    loss: '100'
    correlation: '100'
  direction: to
  externalTargets:
    - <external-enrichment-host>
  duration: 10m
EOF
```

**Cleanup (always runs):**
```
kubectl delete networkchaos discovr-external-blackhole-<run-id> -n <discover-namespace>
```

## Per-action confirmation contract
- Chosen option (A or B); if B, the chaos-mesh CRD presence is verified first
- Current external API p99
- Current discover-job HTTP connection pool active count
- The action command + cleanup command
- The maximum duration the blackhole will be active

## Metrics to capture (every 5 s, minute 7 to minute 20)
- External API success rate (will go to 0 during blackhole)
- External API timeout rate
- Discover-job HTTP connection pool active + idle
- Discover-job thread pool active
- on_discover delivery rate
- on_discover fallback path invocations (if implemented)
- Discover NACK rate (if NACK is the chosen failure mode)
- Circuit breaker state (CLOSED / OPEN / HALF_OPEN)

## SLOs (during blackhole)
- Discover-job HTTP connection pool active ≤ 80 % of capacity (must not pin)
- Discover-job thread pool active ≤ 80 % (must not pin)
- Circuit breaker opens within 30 s of the start of blackhole
- on_discover behavior is either:
  - (a) **Fallback**: returns on_discover within `external.timeout_ms + 500 ms` with degraded data + a flag, OR
  - (b) **Clean failure**: returns no on_discover, but logs/metrics make the failure visible and `discover` does not silently hang
- No DLT growth from the discover-job consumer
- No pod crashes / restarts

## SLOs (after blackhole removed)
- Circuit breaker transitions to HALF_OPEN then CLOSED within 60 s of external recovery
- on_discover delivery rate returns to baseline within 60 s
- No backlog of messages in discover-job consumer (lag stays bounded throughout)

## Cleanup verification
- External API mode reset to healthy
- chaos-mesh CRD deleted (if Option B used)
- Circuit breaker CLOSED
- discover-job HTTP pool back to baseline
