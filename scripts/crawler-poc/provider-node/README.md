# Provider Node — catalog bucket (crawler POC)

A self-contained catalog bucket for the decentralized-catalog crawler, in the current
**baseline + change-files** layout (matches the tech-mart `catalog/` arrangement and the
onix crawler). Run the static server + ngrok tunnel from *this* folder and it serves the
catalog index, baselines, and change files the crawler reads.

> Index-only: this bucket serves just the catalog index chain. There is no DeDi manifest /
> pointer file here — the onix crawler is pointed straight at the catalog index URL
> (`CRAWLER_INDEX_URLS`, or the `/crawl` endpoint). The registry/DeDi path is out of scope.

## Layout

```
provider-node/
  catalog/catalog-index.json                              # the index: participantId, version, catalogs[] {baseline, changes[]}
  catalog/catalogs/CAT-GROCERY-FRESHMART-100.v1.json      # a baseline (full catalog at version 1)
  catalog/changes/CAT-GROCERY-FRESHMART-100.v2.changes.json # a change file (upserts/removals for v2)
  serve.sh                                                # serve THIS folder over ngrok, one command
  publish-ngrok.js                                        # re-point the index's file URLs at the tunnel + recompute digest/size
```

The crawler verifies each file by its own signed digest in the index: `baseline.digest` and
every `changes[].digest` are the sha-256 of that file's exact bytes. `size` on each entry
drives the crawler's baseline-vs-changes cutover. Any edit to a baseline or change file means
its digest + size must be recomputed — which `publish-ngrok.js` does over the actual bytes.

## Serve it

```bash
cd scripts/crawler-poc/provider-node

./serve.sh start          # static server on :8080 + ngrok tunnel; prints the public URL
./serve.sh status         # what's running + current public URL
./serve.sh stop           # stop both

# after start, point the index's file URLs at the tunnel and recompute digest/size:
NGROK_URL=https://<your-id>.ngrok-free.dev node publish-ngrok.js
```

`serve.sh` uses a fixed reserved ngrok domain (edit `DOMAIN` in the script to change it, or
blank it for a random URL). Requires `ngrok` installed and authenticated once
(`ngrok config add-authtoken <TOKEN>`).

## Point the crawler at it

The crawler takes the **catalog index URL** directly:

```
CRAWLER_INDEX_URLS=https://<your-tunnel-host>/catalog/catalog-index.json
```

or trigger an immediate pull via the crawl endpoint:

```bash
curl -s -X POST http://localhost:8091/crawl \
  -H "Content-Type: application/json" \
  -d '{"indexUrl":"https://<your-tunnel-host>/catalog/catalog-index.json"}'
```

## Publishing an update (the demo loop)

1. Edit a baseline in `catalog/catalogs/` (or add a change file in `catalog/changes/` and add
   its entry to `catalog/catalog-index.json`, bumping the index `version`).
2. `NGROK_URL=<tunnel> node publish-ngrok.js` — rewrites the file URLs + recomputes digest/size.
3. The next crawl pass (or a `/crawl` trigger) picks up the new version.

## Notes

- `.serve/` (ngrok pids + logs) is git-ignored — never committed.
- `signature` fields in the index are left empty — per-file signature verification is Phase 2;
  the crawler verifies digests only today.
