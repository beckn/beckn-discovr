---
description: >
  Space-aware Docker bootstrap for Beckn Discovr. Optionally cleans up Docker artifacts
  when disk is low, then builds images, runs docker compose up, and waits for required
  services to become healthy. Designed to run from the repo root.
---

Bootstrap the Beckn **Discovr** Docker stack from scratch (or reset it cleanly).

## Assumptions

- You are running from: `/Users/manju/Documents/Projects/Beckn/beckn-discovr`
- Compose file: `docker-compose.yml`
- Docker network used by compose: `beckn-network` (as defined in compose)

## Step 0 — Preflight

1. Confirm Docker is available:
   - `docker version`
2. Show current disk + Docker usage:
   - `df -h .`
   - `docker system df`

## Step 1 — Space-aware cleanup (only if needed)

If disk is healthy, **skip** cleanup.

If disk is low (e.g. < 10–15 GB free) or Docker usage is very large:

1. Bring down the stack first (safe, removes orphan containers):
   - `docker compose -f docker-compose.yml down --remove-orphans`

2. Minimal cleanup (safe defaults):
   - `docker builder prune -f`
   - `docker image prune -f`
   - `docker volume prune -f` (only if you are OK deleting unused volumes)

3. Aggressive cleanup (ONLY if you still have space issues and you accept losing caches):
   - `docker system prune -af`

## Step 2 — Build images

Build all services:
- `docker compose -f docker-compose.yml build`

If you need a clean rebuild:
- `docker compose -f docker-compose.yml build --no-cache`

## Step 3 — Start the stack

Start all services:
- `docker compose -f docker-compose.yml up -d`

## Step 4 — Wait for health

Poll until required containers are `Up` (and `healthy` where healthchecks exist).

Common containers in this stack include:
- `discovery-service-postgres`
- `discovery-elasticsearch` (optional)
- `ollama` + `ollama-init` (optional, for embeddings)
- `catalog-discover-job`
- `catalog-publish-job`
- `response-dispatcher`

Commands to use while waiting:
- `docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(discovery|catalog|elastic|ollama|dispatcher|postgres)"`
- `curl -s http://localhost:8082/actuator/health` (discover job, if mapped)
- `curl -s http://localhost:9200` (elasticsearch, if enabled)

## Step 5 — Postflight (quick sanity)

1. Verify discover health via actuator:
   - `curl -s http://localhost:8082/actuator/health`
2. Suggest next step:
   - Ingest a catalog via `POST /catalog/push`, then run `GET/POST /beckn/discover` scenarios.

