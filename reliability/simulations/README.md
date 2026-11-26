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
3. Pause discover consumers (required):

```bash
./reliability/scripts/pause-discover-consumers.sh --check
# Ops apply (mutates staging — not for the Phase-1 read-only agent):
./reliability/scripts/pause-discover-consumers.sh --apply-env
# or: ./reliability/scripts/pause-discover-consumers.sh --apply-scale
```

4. Point discover auth at the registry mock (`SIGNATURE_REGISTRY_BASE_URL` → mock URL)
   and start / deploy the mock:

```bash
./reliability/scripts/mock-registry.sh start --mode cache-hit
# or --mode cache-miss | registry-down
```

5. Sync / run via the dispatcher:

```bash
# Dry-run (validates files + prints the JMeter command; no SSH)
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000 --registry-mode cache-hit --dry-run

# Live staging (needs filled cluster.yaml + JMeter VM)
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000 --registry-mode cache-hit
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000 --registry-mode cache-miss
```

### Pass / fail

Assert against `../config/slos-100m.yaml` S1 rows. Write `verdict.md` under
`../reports/<UTC>/sim-S1-<mode>/`. Update `scorecard-template.md`.

### Local Compose note

S1 can smoke against Docker Compose if auth is disabled
(`SIGNATURE_AUTH_ENABLED=false`) **or** the registry mock is wired as
`SIGNATURE_REGISTRY_BASE_URL`. Consumers: set
`DISCOVERY_KAFKA_LISTENER_ENABLED=false` or `SPRING_PROFILES_ACTIVE=sim-s1`.
Full 12k TPM evidence still requires staging + filled `cluster.yaml`.
