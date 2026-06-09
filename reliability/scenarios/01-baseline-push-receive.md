---
id: "01"
name: "Baseline push receive + index"
group: indexing
duration_min: 5
---

## Goal
Establish single-user latency floor for `/catalog/push` and confirm the indexing path (PG + ES) is healthy. Gate scenario.

## Catalog shape (used by every indexing scenario)
- Source: Catalg distributes catalogs to Discovr because Discovr is subscribed to the catalog's `visibleTo` network
- For load testing, JMeter posts directly to Discovr's `/catalog/push` endpoint with payloads matching what Catalg would send
- Per request: 1 push body with 2 catalogs × 50 resources + 10 offers each
- `updateMode`: MERGE

## Load shape
- JMeter script: `push-baseline.jmx`
- Concurrent users: 1
- Ramp-up: 0 s
- Duration: 5 min

## JMeter command
```
-Jusers=1 -Jduration=300 -JupdateMode=MERGE -JresourceCount=50 -JofferCount=10 -JcatalogsPerReq=2
```

## Metrics to capture (poll every 30 s)
| Metric | Source | Filter |
|---|---|---|
| HTTP p50 / p95 / p99 | ClickStack | `service.name=catalog-publish-job` `http.route=/catalog/push` |
| HTTP 2xx / 4xx / 5xx | ClickStack | same |
| catalog-publish-job consumer lag (if push triggers Kafka downstream) | ClickStack | `consumer_group=catalog-publish-job` |
| ES bulk indexer accept / reject count | ClickStack | bulk indexer metrics |
| PG row count delta in `catalog_index`, `item_index` | psql (read-only) | pre vs post |
| ES `_count` on the indexed catalog index | ES `_count` API | pre vs post |

## SLOs
- Error rate ≤ 0.1 %
- p50 ≤ 100 ms
- p99 ≤ 500 ms
- ES bulk reject count = 0
- No 5xx
- Indexed doc count delta == published resource count (no message loss)

## Notes
- Smoke gate. Do not move to scenario 02 if this fails.
- Verify PG and ES row counts are in sync after the run.
