# S3 — Query-engine / headline discover (corpus-backed)

| | |
|--|--|
| **Entry** | `POST /beckn/discover` with path-labeled intents (T / G / J / J+G) **or** Gradle engine IT (local-smoke) |
| **Exit** | Engine result timings (ClickStack `engine.es` / `engine.pg`) + ACK; consumers **must be enabled** |
| **Proves** | BM25 + PostGIS p99 **against indexed corpus** at profile `1M\|10M\|50M\|100M` |
| **Driver** | `jmx/s3-query-headline.jmx` via `scripts/sim-run.sh S3` |
| **Corpus** | Required — `corpus-verify.sh --profile <p>` PASS before live run |

## Pass targets (`slos-100m.yaml`)

| Metric | Target |
|--------|--------|
| ES BM25 p99 | ≤ 200 ms |
| PG PostGIS / J p99 | ≤ 150 ms |
| Chain p99 | ≤ 400 ms |

## Run

```bash
export ES_URL=… PG_URL=… ES_ALIAS=beckn-catalog
./reliability/scripts/corpus-verify.sh --profile 1M   # counts via alias + golden T/G probes
./reliability/scripts/sim-run.sh S3 --profile 1M --path T --qps 200 --dry-run
./reliability/scripts/sim-run.sh S3 --profile 1M --path mix --qps 200
```

`--path mix` runs **sequential weighted** JMeter sub-runs (T / G / J / JG).  
JMeter JTL = ACK latency; **engine p99 from ClickStack**.

Artifacts: `reliability/reports/<UTC>/sim-S3-<profile>/` with `run-meta.yaml` including `corpus.profile` and `query.path`.
