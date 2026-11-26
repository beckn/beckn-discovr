# S5 — Full e2e headline (corpus-backed)

| | |
|--|--|
| **Entry** | BAP → discover → Kafka → engines → dispatcher → callback sink / onix |
| **Exit** | `on_discover` received (ClickStack e2e timer) |
| **Proves** | Headline e2e p99 for BM25+PostGIS paths at the **verified** corpus size |
| **Driver** | `jmx/s5-e2e-headline.jmx` via `sim-run.sh S5` |
| **Corpus** | Required — same profile as S3 column |

## Pass targets

| Metric | Target |
|--------|--------|
| e2e headline p99 (J/G/T/J+G) | ≤ 1.5 s (stretch 1.0 s) |
| e2e chain p99 | ≤ 2.0 s |
| Callback success | ≥ 99.9% |
| Kafka lag sustained | &lt; 1k |

## Run

```bash
./reliability/scripts/pause-discover-consumers.sh --resume-env
./reliability/scripts/corpus-verify.sh --profile 1M
./reliability/scripts/sim-run.sh S5 --profile 1M --headline-only --qps 200 --dry-run
./reliability/scripts/sim-run.sh S5 --profile 1M --headline-only --qps 200
```

Pair JMeter JTL with ClickStack e2e queries. Do **not** mark the corpus scorecard column PASS on ACK alone.
