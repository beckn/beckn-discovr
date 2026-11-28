# Corpus ladder — operator runbook (1M → 10M → 50M → 100M)

**Design:** Agent Store `docs/corpus-ladder-simulation.md`  
**Profiles:** `../config/corpus-profiles.yaml`  
**Praveen lock:** measure against **indexed** corpus sizes; S1 ACK-only is not enough alone.

## Prerequisites

1. Fill `../config/cluster.yaml` (and `simulation.corpus_profile`).
2. Export datastore URLs for seed/verify:

```bash
export ES_URL='http://…:9200'
export PG_URL='postgres://…/discovr'
export DISCOVER_URL='https://…/beckn/discover'   # optional for verify smoke
export PUSH_URL='https://…/catalog/push'         # S6 publish mode
```

3. **Consumers ENABLED** for S3/S5/S6 (resume if S1 paused them):

```bash
./reliability/scripts/pause-discover-consumers.sh --resume-env
```

## Step 0 — local-smoke (always first)

```bash
./reliability/scripts/corpus-seed.sh --profile local-smoke --dry-run
# With Compose/staging URLs:
./reliability/scripts/corpus-seed.sh --profile local-smoke --mode bulk
./reliability/scripts/corpus-verify.sh --profile local-smoke
./reliability/scripts/sim-run.sh S3 --profile local-smoke --path T --qps 20 --dry-run
```

## Step 1 — **1M** (first real rung)

On a GCP sim VM / staging (not a tiny agent box):

```bash
./reliability/scripts/corpus-seed.sh --profile 1M --dry-run
./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk
./reliability/scripts/corpus-verify.sh --profile 1M

./reliability/scripts/sim-run.sh S3 --profile 1M --path mix --qps 200
# --path mix expands to sequential weighted sub-runs (T45% / G25% / J20% / JG10%)
# Or run a single path: --path T|G|J|JG
./reliability/scripts/sim-run.sh S5 --profile 1M --path T --headline-only --qps 200
./reliability/scripts/sim-run.sh S6 --profile 1M --mode sample

# Engine / e2e p99: JMeter JTL is ACK-only for S3/S5 — pull ClickStack timers
# (engine.es / engine.pg / e2e.on_discover) labeled by corpus.profile=1M.

# Optional ACK isolation on the same corpus
./reliability/scripts/pause-discover-consumers.sh --apply-env
./reliability/scripts/sim-run.sh S1 --profile 1M --registry-mode cache-hit
# --intent still works on S1 without --path overwriting it:
# ./reliability/scripts/sim-run.sh S1 --profile 1M --intent intent-j.json --registry-mode cache-hit
./reliability/scripts/pause-discover-consumers.sh --resume-env
```

Fill scorecard column **1M** in `scorecard-template.md`.

## Step 2 — **10M**

Requires larger ES/PG (see `corpus-profiles.yaml` `hardware_hint`). Guard:

```bash
export CORPUS_ALLOW_LARGE=1
./reliability/scripts/corpus-seed.sh --profile 10M --mode bulk
./reliability/scripts/corpus-verify.sh --profile 10M
./reliability/scripts/sim-run.sh S3 --profile 10M --path mix --qps 200
./reliability/scripts/sim-run.sh S5 --profile 10M --headline-only --qps 200
```

## Step 3 — **50M** → Step 4 — **100M**

Same pattern with `CORPUS_ALLOW_LARGE=1` and Tier B-shaped datastores. Prefer dedicated index/DB wipe between rungs so counts are not additive.

```bash
export CORPUS_ALLOW_LARGE=1
./reliability/scripts/corpus-seed.sh --profile 50M --mode bulk
# …
./reliability/scripts/corpus-seed.sh --profile 100M --mode bulk
```

## Partial loads / agent VM

```bash
# Generate plan only
./reliability/scripts/corpus-seed.sh --profile 1M --dry-run

# Tiny subset of a large profile (dev)
./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk --limit 5000
./reliability/scripts/corpus-verify.sh --profile 1M --expect 5000
```

## GCP note

This agent environment often has **no gcloud credentials**. Use the playbook in Agent Store `docs/corpus-ladder-simulation.md` §8 / `docs/s1-gcp-simulation-report.md` to auth, size `discovr-sim-corpus-01`, then run the 1M sequence above.
