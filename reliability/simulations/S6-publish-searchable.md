# S6 — Publish → searchable (corpus ladder)

| | |
|--|--|
| **Entry** | Catalog publish inject (`/catalog/push` or bulk seed) |
| **Exit** | Resource countable in ES/PG **and** discoverable |
| **Proves** | Dual-write / indexing keeps up; push→searchable p99; bulk reject = 0 |
| **Driver** | `corpus-seed.sh --mode publish` + `jmx/s6-publish-burst.jmx` + `corpus-verify.sh` |
| **Corpus** | Sample publish on each rung; full ladder size usually via `--mode bulk` |

## Pass targets

| Metric | Target |
|--------|--------|
| push→searchable p99 | 2–5 s |
| ES bulk reject | 0 |

## Run

```bash
# Fast ladder fill (bulk dual-write)
./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk

# Production-shaped publish samples
./reliability/scripts/corpus-seed.sh --profile local-smoke --mode publish
./reliability/scripts/sim-run.sh S6 --profile 1M --mode sample --dry-run
./reliability/scripts/sim-run.sh S6 --profile 1M --mode sample
```

After publish, wait for lag then `corpus-verify.sh`. Bulk mode already invokes verify at the end of `corpus-seed.sh`.
