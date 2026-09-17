# Discovr Helm Charts

Three standalone Helm charts for deploying Beckn Discovr's Java services to any
Kubernetes cluster. They are generic and self-contained — not tied to any
specific GitOps setup, fabric automation pipeline, or environment. Anyone
running a Beckn network can use them to stand up Discovr's discovery pipeline
independently.

| Chart | Deploys |
|-------|---------|
| `catalog-discover-job/` | Handles Beckn `discover` requests from BAPs, queries the catalog index (PostgreSQL/PostGIS + Elasticsearch), and publishes `on_discover` responses. |
| `catalog-publish-job/` | Ingests catalog data pushed from Beckn Catalg (`catalog/push`), persists it to PostgreSQL, and indexes it into Elasticsearch. |
| `response-dispatcher/` | Consumes `on_discover` responses, signs them with a Beckn HTTP signature, and delivers them to the requesting BAP's callback URL. |

## Prerequisites

Each service needs external infrastructure reachable from the cluster:

- **PostgreSQL with PostGIS** — catalog index storage (used by `catalog-discover-job` and `catalog-publish-job`)
- **Elasticsearch** — text/spatial search index (used by `catalog-discover-job` and `catalog-publish-job`)
- **Kafka** — the async backbone connecting all three services (`discovr.publish.*`, `discovr.discover.*`, `discovr.dispatcher.*` topics)

None of this infrastructure is provisioned by these charts. For what each
service expects of these dependencies (schemas, topics, indices), see:

- [`../DEPLOYMENT.md`](../DEPLOYMENT.md) — the existing deployment reference
- [`../../docker-compose.yml`](../../docker-compose.yml) — a working local stack showing every service wired together, useful as a reference for required connectivity and configuration shape

## Installing a chart

Each chart ships with an empty `env: []`, `config: {}`, and `credentials: {}`
in its `values.yaml` — nothing is deployed with implicit assumptions. You must
supply your own values file with real connection details before the
Deployment will start successfully.

Start from the `values-example.yaml` in the chart's directory, which lists the
actual environment variables the Spring Boot application reads (Kafka
brokers, PostgreSQL connection, Elasticsearch hosts, topic names, Beckn
signature/auth config, etc.), copy it, fill in your values, and install:

```bash
cp discovr-deployment/helm-charts/catalog-discover-job/values-example.yaml my-values.yaml
# edit my-values.yaml with your real hosts, credentials, etc.

helm install catalog-discover-job ./discovr-deployment/helm-charts/catalog-discover-job -f my-values.yaml
```

Repeat for `catalog-publish-job` and `response-dispatcher`, each with its own
`values-example.yaml`.

## Configuration shape

- `env` — a list of `{name, value}` or `{name, valueFrom}` Kubernetes env
  entries, injected directly into the container.
- `config` — mounted as a ConfigMap at `/app/config/application.yml` inside
  the container (a Spring Boot config file overlay), if your deployment needs one beyond env vars.
- `credentials` — a `stringData` map merged into a `<release>-credentials`
  Kubernetes Secret, mounted into the container via `envFrom` as plain env
  vars. Use this for passwords, tokens, and signing keys — never put secrets
  in `env` or `config`.

## Probes

Liveness/readiness/startup probes are **disabled by default** in each chart's
`values.yaml`. The example values files enable them against each service's
Spring Boot Actuator health endpoints — adjust thresholds for your cluster's
startup characteristics.
