# Sample provider node — DeDi format (Decentralized Catalog crawler POC)

These files match the **DeDi-native format** a provider actually hosts (verified against a
live reference node). The crawler consumes them exactly as-is.

```
sample-bucket/                          maps to the provider's domain root
├── .well-known/dedi.json               MANIFEST  (dedi-manifest)  — MUST be at domain root
├── dedi/beckn-catalogs.dedi.json       INDEX     (dedi-file, registry of catalog records)
└── catalogs/
    ├── CAT-ELECTRONICS-2026-000.json   catalog part (laptops)     — public, multi-part
    ├── CAT-ELECTRONICS-2026-001.json   catalog part (phone + audio)
    └── CAT-EON-EXCLUSIVE-2026.json     catalog             — network-restricted (skipped)
```

The index also lists a **RETIRED** record (`CAT-ELECTRONICS-2025`) with no `parts`, to show
retirement signalling.

## The digest chain (why order matters)
Integrity chains top-down, so the files were generated bottom-up:

```
manifest.files[].digest  ──▶ verifies ──▶  index (beckn-catalogs.dedi.json)
index records[].details.parts[].digest ──▶ verifies ──▶  each catalog file
```

- `manifest.files[].digest` = `sha-256` of the **index** bytes.
- each `parts[].digest` = `sha-256` of that **catalog part** bytes.
- Note the prefix is **`sha-256:`** (hyphen), per the DeDi format — not `sha256:`.

Signatures (`proof.jws`) are **`UNSIGNED_LOCAL_TEST_DATA`** here — signature verification is
deferred for the POC. Note the signature is an **embedded `proof` block** (JWS, JCS
canonicalization), *not* a detached `.sig` file.

## Host it (domain or bucket — the crawler doesn't care)
Any static host works. The only hard rule: the manifest must be reachable at
`https://<domain>/.well-known/dedi.json`.

```bash
# object store example (GCS)
gsutil mb gs://techmart-dedi
gsutil iam ch allUsers:objectViewer gs://techmart-dedi
gsutil -m cp -r .well-known dedi catalogs gs://techmart-dedi/
```

> If your host/domain differs, update the `url` fields in `dedi/beckn-catalogs.dedi.json`
> and `files[].url` in `.well-known/dedi.json` to match.

## Recompute the chain after editing a catalog
Edit bottom-up, because each digest feeds the file above it:

```bash
# 1. catalog part digests → paste into index parts[].digest, bump that record's version
cd catalogs && for f in *.json; do printf "%s  sha-256:%s\n" "$f" "$(shasum -a 256 "$f" | awk '{print $1}')"; done && cd ..

# 2. index digest → paste into manifest files[].digest
printf "index  sha-256:%s\n" "$(shasum -a 256 dedi/beckn-catalogs.dedi.json | awk '{print $1}')"
```

## Incrementality test
1. Host as-is, run a crawl → both public catalogs' parts get pushed; the network-restricted
   one is skipped; the RETIRED one is handled per OQ-1.
2. Edit `CAT-ELECTRONICS-2026-001.json`, recompute its digest into the index, bump that
   record's `version` (42 → 43), recompute the index digest into the manifest, re-host.
3. Run again → only that one part is refetched and pushed; the unchanged part is skipped.
