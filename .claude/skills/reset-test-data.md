---
description: >
  Safe-by-default reset for local Discovr verification runs. Stops compose and optionally removes volumes.
  Does NOT delete volumes unless explicitly chosen.
---

Reset local Discovr state safely.

## Step 1 — Stop stack (non-destructive)

From repo root:
```bash
docker compose -f docker-compose.yml down --remove-orphans
```

## Step 2 — Destructive reset (ONLY if you really need a clean slate)

This removes volumes (Postgres/ES data) and wipes state:
```bash
docker compose -f docker-compose.yml down -v --remove-orphans
```

If disk is critically low and you accept losing Docker caches:
```bash
docker builder prune -f
docker system prune -af
```

