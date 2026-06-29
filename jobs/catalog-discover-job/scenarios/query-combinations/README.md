# Discover query-combination verification scenarios

Seven Beckn v2.0 `discover` request bodies — one per J/G/T combination —
for end-to-end verification of `catalog-discover-job` against the live
Docker stack.

| File | J | G | T | Route | Engine flow |
|------|---|---|---|-------|-------------|
| `case-1-jsonpath-only.json` | ✓ |   |   | Path B | PSQL JSONPath |
| `case-2-geo-only.json`      |   | ✓ |   | Path C | PSQL/ES spatial |
| `case-3-text-only.json`     |   |   | ✓ | Path D | ES text (BM25 / KNN) |
| `case-4-jsonpath-geo.json`  | ✓ | ✓ |   | Path A | PSQL combined (one query) |
| `case-5-geo-text.json`      |   | ✓ | ✓ | Path C | ES (text + geo) |
| `case-6-jsonpath-text.json` | ✓ |   | ✓ | Chain  | ES text → IDs → PSQL JSONPath |
| `case-7-jsonpath-geo-text.json` | ✓ | ✓ | ✓ | Chain | ES text+geo → IDs → PSQL JSONPath+geo |

Each request targets the EV charging schema with `connectorType == "CCS2"`,
a 5 km radius around Bangalore MG Road (`12.9716, 77.5946`), and the
free-text query "fast charging station".

## Run

```bash
# 1. Start the local stack (from repo root):
docker compose up -d

# 2. Verify the service is healthy:
curl -s http://localhost:8082/actuator/health

# 3. Fire all 7 scenarios:
./run-all.sh

# Optional overrides:
HOST=http://localhost:8082 METHOD=GET ./run-all.sh
```

The `run-all.sh` script POSTs each body to `/beckn/discover` and prints
`HTTP 200/202 ✓` per scenario. Async POST returns ACK immediately —
the `on_discover` callback lands at the BAP callback URL configured in
auth/subscriber registry.

## What each case verifies

- **Case 1 (J)** — `executeFilterQuery` → PSQL `WHERE jsonpath_match(...)`.
- **Case 2 (G)** — `executeSpatialQuery` → ES `geo_shape` or PSQL `ST_DWithin`.
- **Case 3 (T)** — `TextSearchEngine.search` → BM25 or KNN (depends on engine config).
- **Case 4 (J+G)** — `executeCombinedQuery` → PSQL single-roundtrip combined query.
- **Case 5 (G+T)** — `executeSpatialQuery` with text → ES bool+geo or KNN+geo.filter.
- **Case 6 (J+T)** — `fetchMatchingResourceIds` (BM25 or KNN) → PSQL JSONPath on IDs.
- **Case 7 (J+G+T)** — `fetchMatchingResourceIds` with geo (BM25 or KNN) → PSQL J+G on IDs.

For cases 3, 5, 6, 7 you can re-run after flipping
`discovery.text-search.engine` between `native-els` and `els-semantic-search`
to verify both BM25 and semantic execution.

## Authentication

The scripts do **not** sign requests. Either:

1. **Disable auth** in `application.yml` for verification only:
   ```yaml
   beckn-auth:
     required: false
   ```
2. **Or** pre-compute the Beckn HTTP signature and pass it via
   `AUTH_HEADER="Signature ..." ./run-all.sh`.

## Inspecting results

```bash
# Tail the discover-job logs for routing markers:
docker compose logs -f catalog-discover-job | grep -E 'route-selected|chain|event='

# Check the on_discover callbacks at the response-dispatcher:
docker compose logs -f response-dispatcher | grep on_discover
```

Look for these log markers per case:
- `route-selected path=A|B|C|D|chain`
- `chain.es-candidates-fetched mode=bm25|semantic ids=N`
- `chain.psql-allowlist-applied allowlistSize=N`
- `query-completed durationMs=N`
