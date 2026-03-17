# beckn-discovr — AI/Contributor Guide

This repository contains services/jobs for the Beckn One Catalog Distribution System (CDS) discovery + ingestion flow. Most code lives under `jobs/` as independent Gradle/Spring Boot projects, each with its own Gradle wrapper.

## Repo map

- `jobs/catalog-discover-job/` — **Discovery Service** (aka `discovery-service`): HTTP API `/beckn/discover`, optional Kafka consumer, Postgres-backed query engine with optional Elasticsearch/NLWeb text search integration.
- `jobs/catalog-publish-job/` — **Catalog Publish Job**: ingestion/persistence bridge (Kafka + Postgres; distribution can be disabled).
- `jobs/response-dispatcher/` — **Seeker Notifier Job**: Kafka consumer/producer that forwards messages between topics with DLT handling.
- `docker-compose.yml` — local stack for Postgres + Elasticsearch + Ollama + jobs + demo UI.
- `reference-apps/` — demo UI and supporting reference apps.
- `config/` — shared configuration assets (e.g., Elasticsearch index template used by docker compose).

## Build / test (canonical commands)

Each job is built and tested from its own directory (each has its own `gradlew`).

- Unit tests:

```bash
cd jobs/<job-name>
./gradlew test --no-daemon
```

- Integration tests (only some jobs define them; CI runs this for `catalog-publish-job`):

```bash
cd jobs/<job-name>
./gradlew integrationTest --no-daemon
```

- Full verification:

```bash
cd jobs/<job-name>
./gradlew check --no-daemon
```

### Java version

CI uses **JDK 17**. Keep code compatible with Java 17 and Spring Boot versions declared per job.

## CI expectations (GitHub Actions)

Pull requests to `main` run:

- **Gradle tests**:
  - `jobs/catalog-discover-job`: `./gradlew test`
  - `jobs/catalog-publish-job`: `./gradlew test` + `./gradlew integrationTest`
- **Trivy FS + SBOM scanning** (non-blocking): generates CycloneDX SBOMs via `cyclonedxDirectBom` for the Java jobs and scans them.

When changing dependencies, ensure `./gradlew cyclonedxDirectBom` still works for impacted jobs.

## Running locally (Docker Compose)

The root `docker-compose.yml` brings up:

- Postgres/PostGIS (`postgres-discovery`, exposed on host `5434`)
- Elasticsearch (`9200`)
- Ollama + model init job (pulls embedding + small LLM models)
- `catalog-publish` (host `8085`)
- `catalog-discover-job` (host `8082`)
- Demo UI (host `5173`)

### One-time setup

`docker-compose.yml` uses an **external** network named `beckn-network`. Create it once:

```bash
docker network create beckn-network
```

### Start/stop

```bash
docker compose up -d
docker compose logs -f
docker compose down
```

### Health checks

- `catalog-discover-job`: `GET http://localhost:8082/actuator/health`

## Configuration conventions

Prefer configuration through:

- Spring `application.yml` / profile-specific YAML (e.g., `application-docker.yml`)
- Environment variables (Docker Compose uses many `APP_*`, `KAFKA_*`, `POSTGRES_*`, etc.)

When adding new configuration:

- Keep **property names consistent** with existing patterns in that job.
- Prefer **typed properties** classes (`@ConfigurationProperties`) where already used.
- Document new required env vars in the job’s README if they affect runtime.

## Kafka conventions

Several jobs consume/produce Kafka messages.

- Keep topic names **configurable** (do not hardcode).
- When adding consumers, prefer **manual acknowledgment** if the job’s design relies on precise failure routing (DLT) and retry control.
- Ensure failed message handling is explicit (logging + DLT topic where applicable).

## Coding conventions for changes

When you (or an AI assistant) make changes:

- Keep changes **scoped to the relevant job** under `jobs/<job>/...`.
- Follow existing Spring Boot patterns in that job (package layout, configuration style).
- Add/adjust tests in the same job:
  - Unit tests under `src/test/java/...`
  - Integration tests when the job already uses Testcontainers (e.g., `catalog-publish-job`).
- Avoid introducing new build steps that CI doesn’t run, unless you also update CI.

## Common “gotchas”

- **Per-job Gradle wrappers**: run `./gradlew ...` from the job directory you’re working on.
- **External Docker network**: `beckn-network` must exist or compose will fail.
- **Integration tests and Testcontainers**: `catalog-publish-job` uses a dedicated `integrationTest` task; CI runs it, so keep it stable and deterministic.

