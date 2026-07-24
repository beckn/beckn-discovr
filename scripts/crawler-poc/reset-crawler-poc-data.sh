#!/usr/bin/env bash
#
# Reset the crawler-POC data WITHOUT tearing the stack down.
#
# Full reset: clears the discovered catalog data (Postgres), the crawler's sync
# bookkeeping, AND the registered sources (crawler_source), and drops the
# Elasticsearch catalog indices. After this the crawler has nothing to crawl until
# a source is re-added (e.g. via the reference UI).
#
# Leaves untouched: Flyway history, PostGIS/tiger/topology system tables.
#
# Usage:   ./scripts/reset-crawler-poc-data.sh
# Env overrides (defaults shown):
#   PG_CONTAINER=postgres  PG_USER=catalog_user  PG_DB=catalog_db
#   ES_URL=http://localhost:9200  ES_INDEX_PATTERN=beckn-catalog*
#
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-postgres}"
PG_USER="${PG_USER:-catalog_user}"
PG_DB="${PG_DB:-catalog_db}"
ES_URL="${ES_URL:-http://localhost:9200}"
ES_INDEX_PATTERN="${ES_INDEX_PATTERN:-beckn-catalog*}"
# Restart the stateless app tier after the wipe (crawler drops in-memory ETag state;
# the others are harmless-but-deterministic). Set RESTART_SERVICES=false to skip.
# Datastores (postgres/elasticsearch/kafka/zookeeper) are NEVER restarted here.
RESTART_SERVICES="${RESTART_SERVICES:-true}"
APP_CONTAINERS="${APP_CONTAINERS:-crawler ingestion discover dispatcher}"

echo "==> Truncating Postgres catalog data + crawler sync state + sources (${PG_DB})"
docker exec -i "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB}" -v ON_ERROR_STOP=1 <<'SQL'
TRUNCATE TABLE
  item,
  item_location_collection,
  provider_offer,
  catalog_part_state,
  index_crawl_state,
  crawler_source
RESTART IDENTITY CASCADE;
SQL

echo "==> Dropping Elasticsearch indices matching '${ES_INDEX_PATTERN}'"
# ES blocks wildcard deletes (action.destructive_requires_name=true), so resolve the
# concrete index names first and delete each explicitly. Deleting an index also drops
# any alias pointing at it. No-op when nothing has been indexed yet.
ES_INDICES="$(curl -fsS "${ES_URL}/_cat/indices/${ES_INDEX_PATTERN}?h=index" 2>/dev/null || true)"
if [ -z "${ES_INDICES//[[:space:]]/}" ]; then
  echo "    No matching ES indices — nothing to drop."
else
  for idx in ${ES_INDICES}; do
    curl -fsS -X DELETE "${ES_URL}/${idx}" >/dev/null \
      && echo "    Dropped index: ${idx}" \
      || echo "    Failed to drop index: ${idx}"
  done
fi

echo "==> Verifying data wipe (all should be 0):"
docker exec -i "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB}" -tA -c \
  "SELECT 'items='||(SELECT count(*) FROM item)
        ||' offers='||(SELECT count(*) FROM provider_offer)
        ||' sources='||(SELECT count(*) FROM crawler_source);"

if [ "${RESTART_SERVICES}" = "true" ]; then
  echo "==> Restarting app tier (${APP_CONTAINERS}) — datastores left running"
  # shellcheck disable=SC2086
  docker restart ${APP_CONTAINERS} >/dev/null && echo "    Restarted."
else
  echo "==> Skipping service restart (RESTART_SERVICES=false)."
fi

echo "==> Done."
