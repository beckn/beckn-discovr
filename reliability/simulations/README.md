# Discovr simulation harness (S1–S6)

Path-split gates that prove ACK / Kafka / engine / dispatcher / e2e / publish SLOs
**before** a Tier B prod SKU buy. Extends — does not replace — Phase-1
`reliability/scenarios/`.

| Sim | Status in repo | Driver |
|-----|----------------|--------|
| **S1** ACK-only | **Implemented (this slice)** | `jmx/s1-ack-only.jmx` + `scripts/sim-run.sh` |
| S2 Kafka-in | Follow-on | `kafka-inject.sh` |
| S3 Query engine | Follow-on | Gradle `*.simulation.*` |
| S4 Dispatcher→onix | Follow-on | inject + mock-onix |
| S5 Full e2e | Follow-on | `s5-e2e-headline.jmx` |
| S6 Publish→searchable | Follow-on | `s6-publish-burst.jmx` |

Design: Agent Store `docs/simulation-harness-design.md`  
Architecture (locked): Agent Store `docs/scalability-100m-architecture.md` §11  
SLOs: `../config/slos-100m.yaml`

## How to run S1

### Prerequisites

1. Fill `../config/cluster.yaml` (same as Phase-1) **and** the `simulation:` block.
2. `gcloud` / `kubectl` pointed at the staging context in `cluster.yaml`.
3. Pause discover consumers (required). Mutating scripts call `verify-cluster.sh`
   and pass `--context` from `cluster.yaml`:

```bash
./reliability/scripts/pause-discover-consumers.sh --check
./reliability/scripts/pause-discover-consumers.sh --apply-env
```

4. Default path is **auth-off** (`SIGNATURE_AUTH_ENABLED=false`). Registry cache
   drills need an in-cluster mock + `--sign-url` (per-sample body digest — a static
   `Authorization` header cannot verify).

5. Sync / run via the dispatcher:

```bash
# Dry-run (validates files + prints the JMeter command; no SSH)
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000 --dry-run

# Live staging — auth-off ACK/Kafka (default)
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000

# Registry drills (signer + in-cluster mock required)
./reliability/scripts/sim-run.sh S1 --registry-mode cache-hit \
  --sign-url https://signer.example/sign-payload --tpm 12000
./reliability/scripts/sim-run.sh S1 --registry-mode cache-miss \
  --sign-url https://signer.example/sign-payload --tpm 12000

# Resume: resets consumer offsets to latest, then re-enables the listener
./reliability/scripts/pause-discover-consumers.sh --resume-env
```

QPS is open-loop (`-JtargetRps` + ConstantThroughputTimer). Record achieved RPS from the JTL.

### Pass / fail

Assert against `../config/slos-100m.yaml` S1 rows. Write `verdict.md` under
`../reports/<UTC>/sim-S1-<mode>/`. Update `scorecard-template.md`.

### Local Compose note

Auth-off smoke: `SIGNATURE_AUTH_ENABLED=false` +
`DISCOVERY_KAFKA_LISTENER_ENABLED=false` (or `SPRING_PROFILES_ACTIVE=sim-s1`).
Local `mock-registry.sh start` is for Compose-on-same-host only — staging pods
must use `simulation.registry_mock_url` (in-cluster Service).
