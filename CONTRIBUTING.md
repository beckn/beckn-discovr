# Contributing to Beckn Discovr

Thanks for your interest in contributing. Beckn Discovr is the catalog **discovery → query → dispatch** pipeline for the Beckn ecosystem: BAPs send `discover` requests, Discovr queries the catalog index, and delivers `on_discover` callbacks. It is not a catalog management service — catalog data is indexed from Beckn Catalg.

## Before you start

- Open an issue describing the bug or feature before submitting a large PR, so we can align on approach.
- Small fixes (typos, docs, obvious bugs) can go straight to a PR.
- This project follows [Beckn Protocol v2.0](https://github.com/beckn/protocol-specifications-v2) — no v1.0 legacy support is accepted.

## Project layout

| Component | Path | Stack |
|-----------|------|-------|
| Catalog Discover Job | `jobs/catalog-discover-job/` | Java 17 · Spring Boot · PostgreSQL/PostGIS · Elasticsearch · Kafka |
| Catalog Publish Job | `jobs/catalog-publish-job/` | Java 17 · Spring Boot · Kafka · PostgreSQL · Elasticsearch |
| Response Dispatcher | `jobs/response-dispatcher/` | Java 17 · Spring Boot · Kafka · RestTemplate |

Each job has its own Gradle wrapper — run `./gradlew` from inside the job directory, not the repo root.

## Building and testing

```bash
cd jobs/catalog-discover-job && ./gradlew test
cd jobs/catalog-publish-job && ./gradlew test
cd jobs/catalog-publish-job && ./gradlew integrationTest   # CI also runs this
cd jobs/response-dispatcher && ./gradlew test
```

A local Docker stack is available for end-to-end testing:

```bash
docker network create beckn-network   # one-time setup
docker compose up -d
```

## Code conventions

- **Constructor injection only** — no `@Autowired` field injection.
- **Parameterized SQL only** — no string concatenation in queries.
- **Secrets via `${ENV_VAR}` only** — never hardcode credentials or keys in YAML, source, or test fixtures.
- **No `Thread.sleep()` in tests** — use deadline-based poll loops.
- **Validate callback URLs before any outbound HTTP POST** — this is an SSRF-sensitive path.
- **No `new ObjectMapper()`** — inject Spring Boot's auto-configured bean.
- **Beckn v2.0 field names only** — e.g. `resources` not `items`, `resourceAttributes` not `itemAttributes`.
- Structured JSON logging (LogstashEncoder) with the shared MDC field set — don't hardcode log strings; add new constants to the relevant `LogEvent.java`.

## Submitting a change

1. Fork the repo and create a branch from `development`.
2. Make your change, with tests for new behavior.
3. Run the relevant job's test suite locally before opening a PR.
4. Open a PR against `development` with a clear description of the change and why it's needed.
5. A maintainer will review for correctness, protocol compliance, and test coverage before merging.

## Reporting bugs

Use GitHub Issues for bugs and feature requests. For security vulnerabilities, see [SECURITY.md](SECURITY.md) instead — do not open a public issue.

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to abide by it.
