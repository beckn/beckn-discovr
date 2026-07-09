# 08 — Observability (Logs, Metrics, Structured Logging)

## Overview
Verify structured JSON logging, metric endpoints, and correct event naming across all Discovr services.

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-40 | Discovr ingestion logs are structured JSON | `docker logs discovr-ingestion` | JSON with `@timestamp`, `level`, `message` fields |
| SC-41 | Discover job logs are structured JSON | `docker logs catalog-discover-job` | JSON with MDC fields |
| SC-42 | Prometheus metrics endpoint | `curl -s http://localhost:8085/actuator/prometheus` | Contains `discovr_publish_success`, `discovr_publish_full_replace`, `discovr_publish_persist_inserted`, `discovr_publish_offer_resolve_success` |
| SC-43 | Response dispatcher logs | `docker logs response-dispatcher` | JSON format with `@timestamp` |

## Verification Depth

### Log event naming
All log events must use `dot.separated.lowercase` format:
- `persist.completed`, `persist.failed`
- `full.replace.deleted`, `full.replace.es.deleted`
- `merge.completed`
- `offer.resolve.completed`, `offer.resolve.skipped`
- `consumer.received`, `consumer.processed`, `consumer.error`
- `es.indexed`, `es.failed`
- `push.received`, `push.rejected`
- `auth.skipped`, `auth.verify.start`, `auth.verify.done`, `auth.verify.failed`

### Metric names (discovr.publish.*)
- `discovr_publish_success_total` (tagged by op)
- `discovr_publish_failure_total` (tagged by op)
- `discovr_publish_message_duration_seconds` (tagged by op)
- `discovr_publish_full_replace_total`
- `discovr_publish_full_replace_deleted_resources_total`
- `discovr_publish_full_replace_deleted_locations_total`
- `discovr_publish_full_replace_deleted_es_docs_total`
- `discovr_publish_merge_total`
- `discovr_publish_persist_inserted_total`
- `discovr_publish_persist_updated_total`
- `discovr_publish_offer_resolve_success_total`
- `discovr_publish_offer_resolve_missing_total`
- `discovr_publish_offer_resolve_failed_total`
