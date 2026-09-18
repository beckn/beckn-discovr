# Discovr Helm Bundle

A single umbrella Helm chart that deploys the full Beckn Discovr discover
pipeline on any Kubernetes cluster — app services, an optional ONIX adapter,
and (by default) the infra they need — with one shared values file instead of
juggling several charts by hand.

```
helm/
  charts/
    catalog-discover-job/   # handles Beckn `discover` requests, queries the catalog index
    catalog-publish-job/    # ingests catalog pushes, persists + indexes them
    response-dispatcher/    # signs and delivers on_discover callbacks to BAPs
    onix-discover/          # Beckn ONIX adapter — recommended in front of the stack
  bundle/
    Chart.yaml              # umbrella chart — declares the 4 charts above + optional infra as dependencies
    values.yaml              # shared defaults: infra wiring (hosts/ports/topics), no secrets, no network identity
  values/
    envs/
      dev.yaml.example       # template for a private per-deployment overlay (secrets, network identity)
```

## Why an umbrella chart

Each service chart under `charts/` is independently generic and installable
on its own. The `bundle/` umbrella wires them together so a real deployment
is a **values change, not a template change**: hostnames, ports, and topic
names are defined once in `bundle/values.yaml` via YAML anchors and reused
everywhere they're needed, instead of being repeated (and able to drift) in
four separate files.

## What's included, and what's optional

`bundle/values.yaml`'s `charts:` block toggles every component:

| Chart | Default | Notes |
|---|---|---|
| `catalog-discover-job`, `catalog-publish-job`, `response-dispatcher` | on | Discovr's own services |
| `onix-discover` | on | Beckn ONIX adapter (BAP/BPP signing + routing). Not strictly required — the job services can be called directly — but recommended for a real network deployment; without it you must handle Beckn HTTP-signature auth and network routing yourself. |
| `postgresql`, `kafka`, `elasticsearch`, `redis` | on | Bitnami subcharts, so `helm install` alone gives you a complete, working stack. Turn any of these off (`charts.<name>: false`) if you already run that infra elsewhere, then point the app charts at it via env overrides. |

**PostgreSQL/PostGIS caveat:** `catalog-discover-job` requires the PostGIS
extension, which the stock Bitnami `postgresql` image does not include. See
the comment above `postgresql:` in `bundle/values.yaml` — you'll need to
either point at your own PostGIS-enabled Postgres (`charts.postgresql:
false`) or build a PostGIS-enabled image on top of `bitnami/postgresql` and
override `postgresql.image` here. This bundle can't ship that image itself
since we don't control publishing a derivative of a third-party base image.

## Installing

```bash
cd discovr-deployment/helm/bundle
helm dependency update

helm install discovr . \
  -f values.yaml \
  -f ../values/envs/my-deployment.yaml \
  --namespace discovery --create-namespace
```

`values.yaml` has no secrets and no network identity — it's safe to commit
and share as-is (and is committed here). Everything specific to *your*
deployment (DB password, ONIX subscriber ID, signing keys, registry URLs)
goes in a second, private values file layered on top with `-f`. Copy
[`values/envs/dev.yaml.example`](../values/envs/dev.yaml.example) as a
starting point — keep the filled-in copy **out of version control** (it's
already gitignored under `values/envs/*.yaml`, matched everything except the
committed `.example` template).

Where those real values should actually live is up to your operational setup
— a private GitOps repo, a secrets manager (External Secrets Operator,
sealed-secrets, Vault), or `kubectl create secret` run out-of-band. This
public repo intentionally stops at "here's the shape of what's needed."

## Upgrading a release

```bash
helm upgrade discovr . -f values.yaml -f ../values/envs/my-deployment.yaml
```

A version bump is just an `image.tag` change in your private overlay —
nothing in `bundle/values.yaml` or the charts needs to change for a routine
release.

## Using a chart standalone

Each chart under `charts/` also works on its own if you don't want the full
bundle:

```bash
helm install catalog-discover-job ./charts/catalog-discover-job -f my-values.yaml
```

In that case you're responsible for wiring `env`/`config`/`credentials`
yourself — the umbrella's shared `values.yaml` is what does that wiring for
you when charts are deployed together.

## Configuration shape (every chart)

- `env` — a list of `{name, value}` or `{name, valueFrom}` Kubernetes env entries.
- `config` — mounted as a ConfigMap file overlay (Spring Boot `application.yml`), where applicable.
- `credentials` — merged into a `<release>-<chart>-credentials` Secret, injected via `envFrom`. Never put secrets in `env` or `config`.

`onix-discover` additionally renders its adapter config via an init
container that substitutes signing/encryption keys from a `<release>-
onix-discover-keyset` Secret at pod startup — the ConfigMap itself never
holds real key material.
