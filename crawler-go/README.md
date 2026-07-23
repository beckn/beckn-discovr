# Decentralized Catalog Crawler — Go port

A faithful Go rewrite of the Java (`../crawler`) service. Same behaviour, same DB schema, same
`CRAWLER_*` env vars, same structured log-event names — it is a **drop-in replacement**: point the
`crawler` service in `docker-compose.crawler-poc.yml` at this directory and nothing else changes.

## What it does (unchanged from the Java version)

Two cadences, both config-driven:

- **Manifest refresh** (long, e.g. daily): read each source's DeDi manifest → cache provider
  identity + where each registry's index lives → integrity checkpoint (does the live index still
  hash to what the manifest promised?).
- **Index poll** (short, e.g. per minute): fetch each index, detect change by the index's own
  digest, diff each catalog record against stored state, and push changed catalogs.

Pushes are **per part**: each changed part is fetched, verified against the digest the index
declares (`sha-256:…` over the exact bytes), and POSTed in its own `catalog/publish` envelope
(`message.catalogs=[part]`) so the publish pipeline's default MERGE accumulates parts into one
catalog. Part state advances only on a `200` Ack. The index row rolls up to
`sync_status ∈ {success, partial, failed}` with an `error_detail` JSON array attributing each
failure to `index_url → catalog → part`; `index_digest` advances every pass and retry is gated on
`sync_status` (a `partial`/`failed` index is re-diffed until it reaches `success`, re-pushing only
the still-failed parts).

## Layout (maps 1:1 to the Java packages)

| Go package | Java equivalent |
|------------|-----------------|
| `cmd/crawler` | `CrawlerApplication` + `StartupLogger` |
| `internal/config` | `CrawlerProperties` + `application.yml` |
| `internal/logging` | `LogEvent` + logback JSON setup |
| `internal/model` | `FeedModels` |
| `internal/digest` | `DigestUtil` |
| `internal/httpclient` | `CrawlerHttpClient` |
| `internal/state` | `StateStore` (+ `migrations/` = Flyway `V1..V4`) |
| `internal/source` | `SourceRegistry` / `Config`/`Db` registries |
| `internal/feedback` | `FeedbackLog` |
| `internal/crawl` | `ManifestResolver`, `IndexPoller`, `Differ`, `Fetcher`, `Pusher`, `Crawler` |
| `internal/scheduler` | `CrawlScheduler` |

## Configuration (same env vars as the Java service)

| Env var | Default | Notes |
|---------|---------|-------|
| `CRAWLER_DB_URL` | — (required) | Accepts the Java `jdbc:postgresql://host:port/db` form; converted to a Go DSN. |
| `CRAWLER_DB_USERNAME` / `CRAWLER_DB_PASSWORD` | — | Injected into the DSN. |
| `CRAWLER_SOURCE` | `config` | `config` (uses `CRAWLER_PROVIDERS`) or `db` (`crawler_source` table). |
| `CRAWLER_PROVIDERS` | — | Comma-separated full DeDi manifest URLs (used when `source=config`). |
| `CRAWLER_PUSH_ENDPOINT` | — (required) | e.g. `http://discovr-ingestion:8080/beckn/catalog/push`. |
| `CRAWLER_MANIFEST_REFRESH_INTERVAL` | `1d` | Spring-style durations (`1d`, `1m`, `30s`) supported. |
| `CRAWLER_INDEX_POLL_INTERVAL` | `1m` | |
| `CRAWLER_HTTP_TIMEOUT` | `30s` | |
| `CRAWLER_MAX_PART_BYTES` | `10485760` | |
| `CRAWLER_HTTP_CACHE_BUST` | `true` | Appends `?cb=` to GETs (defeats CDN edge cache). |
| `CRAWLER_FEEDBACK_LOG_PATH` | `./feedback.log` | |
| `CRAWLER_SCHEDULER_ENABLED` | `true` | `false` disables the timers (for tests). |

## Build / run / test

```bash
go build ./...     # compile
go test ./...      # unit tests (digest, config, model, differ, pusher)
go run ./cmd/crawler   # run (needs the CRAWLER_* env vars set)
```

Docker (build context is this directory):

```bash
docker build -t beckn/decentralized-catalog-crawler-go:0.1 .
```

To use it in `docker-compose.crawler-poc.yml`, change the `crawler` service `build.context`
to `./crawler-go` (or set its `image` to the tag above). The `JAVA_OPTS` env in the compose file is
simply ignored by the Go binary; every `CRAWLER_*` var is honoured identically.

## Notes on parity

- **Migrations**: the four `migrations/*.sql` are byte-identical to the Java Flyway files and are
  applied on startup. Every statement is idempotent (`CREATE TABLE`/`ADD COLUMN IF NOT EXISTS`), so
  the Go and Java services coexist against the same database — whichever runs first creates the
  tables, the other no-ops.
- **Push is async**: a `200` from `/catalog/push` means *accepted/enqueued*, not *persisted* —
  resource-level schema validation happens downstream and returns via `catalog/on_publish` (not
  consumed here). `sync_status` therefore reflects *accepted / rejected-at-gateway / unreachable*.
- **Logs**: structured JSON via `log/slog`. Event names match the Java `LogEvent` constants
  exactly; standard keys are renamed (`msg→message`, `time→@timestamp`) to line up with the Java
  logstash output.
- **IPv4**: the HTTP client forces `tcp4` (mirrors `-Djava.net.preferIPv4Stack=true`) and evicts
  idle keep-alive sockets after 30s.
