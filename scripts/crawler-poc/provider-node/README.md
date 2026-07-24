# Provider Node — DeDi demo bucket (crawler POC)

A self-contained DeDi provider bucket for the decentralized-catalog crawler POC.
Run the static server + ngrok tunnel from *this* folder and it serves the manifest,
index, and catalog that the crawler reads.

## Layout

```
provider-node/
  .well-known/dedi.json                 # DeDi manifest (provider identity + index pointer)
  bucket/beckn-catalogs.dedi.json       # index (records → catalog parts + digests)
  bucket/CAT-GROCERY-FRESHMART-100.json # a catalog part (FreshMart grocery)
  serve.sh                              # serve THIS folder over ngrok, one command
  publish-ngrok.js                      # re-point internal URLs at the tunnel + recompute digests
```

The crawler verifies a sha-256 digest chain: catalog file → index `parts[].digest`,
index → manifest `files[].digest`. Any edit to a catalog means the digests must be
recomputed, which `publish-ngrok.js` does over the actual file bytes.

## Serve it

```bash
cd scripts/crawler-poc/provider-node

./serve.sh start          # static server on :8080 + ngrok tunnel; prints the public URL
./serve.sh status         # what's running + current public URL
./serve.sh stop           # stop both

# after start, point the DeDi chain at the printed tunnel URL and recompute digests:
NGROK_URL=https://<your-id>.ngrok-free.dev node publish-ngrok.js
```

`serve.sh` uses a fixed reserved ngrok domain (edit `DOMAIN` in the script to change it,
or blank it for a random URL). Requires `ngrok` installed and authenticated once
(`ngrok config add-authtoken <TOKEN>`).

## Point the crawler at it

Register the manifest URL as a crawler source (via the reference UI, or `CRAWLER_PROVIDERS`):

```
https://<your-tunnel-host>/.well-known/dedi.json
```

## Notes

- `.serve/` (ngrok pids + logs) is git-ignored — never committed.
- The upstream `publish.js` (which commits + `git push`es to the standalone bucket repo)
  is intentionally **not** included here — inside this repo it would push the wrong repo.
  Use `publish-ngrok.js` (local edits only, no git) for the tunnel workflow.
