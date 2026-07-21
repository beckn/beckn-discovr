# Sample bucket contents — Decentralized Catalog crawler POC

Everything under `beckn/` maps 1:1 to what a provider hosts in a public GCS bucket.
Upload the whole `beckn/` folder to the bucket root and you have a working provider.

```
beckn/
├── manifest.json               stable entry point (crawler is pointed here)
├── index.json                  the catalog map + per-part sha256 digests
├── index.json.sig              detached signature (placeholder — deferred in POC)
├── electronics-2026-000.json   catalog part (laptops)
├── electronics-2026-001.json   catalog part (phone + headphones)
└── eon-exclusive-2026.json     catalog part (network-restricted example)
```

The index also lists a RETIRED catalog (`electronics-2025`) that has no part file —
it exists only to show how retirement is signalled.

## Upload to GCS

```bash
# 1. create a public bucket (name must match the URLs inside index.json / manifest.json)
gsutil mb -l asia-south1 gs://techmart-beckn
gsutil iam ch allUsers:objectViewer gs://techmart-beckn      # public read

# 2. upload the folder
gsutil -m cp -r beckn gs://techmart-beckn/

# 3. verify
curl -I https://storage.googleapis.com/techmart-beckn/beckn/index.json   # note the ETag
```

> If you use a different bucket name, update the `url` values in `index.json` and
> `indexUrl` in `manifest.json` to match.

## Recompute digests after editing a catalog file

The digests in `index.json` must match the exact bytes of each part file. After editing
any catalog part, recompute and paste the value back into `index.json`, then bump `version`:

```bash
cd beckn
for f in electronics-2026-000.json electronics-2026-001.json eon-exclusive-2026.json; do
  printf "%s  sha256:%s\n" "$f" "$(shasum -a 256 "$f" | awk '{print $1}')"
done
```

## Testing incrementality

1. Upload as-is, run a crawl → all ACTIVE public catalogs get pushed.
2. Edit `electronics-2026-001.json`, recompute its digest, update it in `index.json`,
   bump `version` 42 → 43, re-upload.
3. Run the crawl again → only `electronics-2026-001.json` is refetched and pushed;
   the unchanged part is skipped.
