---
description: >
  Inspect Kafka topics and container logs for Discovr runs (discover GET/POST, /catalog/push ingestion, dispatcher delivery).
  Use to localize failures quickly.
---

Use this skill when Discovr behavior is unexpected and you need to trace the path:
**/catalog/push ingest → indexed → /beckn/discover GET/POST → responses → dispatcher delivery**.

## Step 1 — Container status

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(discovery|catalog|elastic|ollama|dispatcher|postgres|kafka|zookeeper)"
```

## Step 2 — Tail logs (last 10 minutes)

```bash
docker logs catalog-publish-job --since=10m --tail=200 2>&1
docker logs catalog-discover-job --since=10m --tail=200 2>&1
docker logs response-dispatcher --since=10m --tail=200 2>&1
docker logs discovery-service-postgres --since=10m --tail=200 2>&1
```

Optional engines:
```bash
docker logs discovery-elasticsearch --since=10m --tail=200 2>&1
docker logs ollama --since=10m --tail=200 2>&1
```

## Step 3 — Kafka topics (defaults)

```bash
KAFKA=kafka

docker exec -it "$KAFKA" kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic discovr.discover.in.requests --from-beginning --max-messages 5 \
  --property print.key=true --property key.separator=" | "

docker exec -it "$KAFKA" kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic discovr.discover.out.responses --from-beginning --max-messages 5 \
  --property print.key=true --property key.separator=" | "

docker exec -it "$KAFKA" kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic discovr.dispatcher.dlt.responses --from-beginning --max-messages 5 \
  --property print.key=true --property key.separator=" | "
```

## Step 4 — Fast interpretation

- **/catalog/push not receiving**: check Catalg delivery logs and Discovr publish job logs (`catalog.push.received`).
- **POST /discover ACK but no callback**:
  - confirm discover job logged `Queued async discovery request`
  - confirm request topic has messages
  - confirm response topic has messages
  - if response topic has messages but no delivery, check dispatcher logs + `discovr.dispatcher.dlt.responses`

