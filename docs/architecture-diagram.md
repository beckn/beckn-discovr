# Beckn Discovr — Architecture Overview

## System Architecture

```mermaid
graph TB
    subgraph External
        BAP["BAP<br/>(Beckn Application)"]
        BPP["BPP<br/>(Beckn Provider)"]
    end

    subgraph "Beckn Discovr"
        subgraph "Catalog Publish Job :8085"
            PushAPI["POST /catalog/push<br/>CatalogPushController"]
            PubConsumer["CatalogPublishConsumer<br/>(Parse → Validate → Persist)"]
        end

        subgraph "Catalog Discover Job :8082"
            DiscoverAPI["POST /beckn/discover<br/>DiscoveryController"]
            DiscConsumer["DiscoveryEventConsumer"]
            QueryEngine["Query Engine<br/>(PostgreSQL + Elasticsearch + NLWeb)"]
            Pipeline["CatalogPipeline<br/>(Filter → Dedup → Prune)"]
            ResponseProc["ResponseProcessor<br/>(on_discover assembly)"]
        end

        subgraph "Response Dispatcher"
            Dispatcher["EventListener<br/>+ HttpService"]
            Signer["SignatureService<br/>(Beckn HTTP Signature)"]
        end
    end

    subgraph Infrastructure
        Kafka["Apache Kafka"]
        PG["PostgreSQL<br/>+ PostGIS"]
        ES["Elasticsearch"]
    end

    %% Publish Flow
    BPP -->|"catalog/publish"| PushAPI
    PushAPI -->|"publish to topic"| Kafka
    Kafka -->|"consume"| PubConsumer
    PubConsumer -->|"index"| ES
    PubConsumer -->|"persist"| PG

    %% Discover Flow
    BAP -->|"discover"| DiscoverAPI
    DiscoverAPI -->|"publish to topic"| Kafka
    Kafka -->|"consume"| DiscConsumer
    DiscConsumer --> QueryEngine
    QueryEngine -->|"spatial query"| PG
    QueryEngine -->|"text search"| ES
    QueryEngine --> Pipeline
    Pipeline --> ResponseProc
    ResponseProc -->|"publish to response topic"| Kafka

    %% Response Dispatch
    Kafka -->|"consume"| Dispatcher
    Dispatcher --> Signer
    Signer -->|"POST on_discover<br/>(signed callback)"| BAP

    style BAP fill:#4A90D9,color:#fff
    style BPP fill:#4A90D9,color:#fff
    style Kafka fill:#E8A838,color:#fff
    style PG fill:#336791,color:#fff
    style ES fill:#00BFB3,color:#fff
```

## Flow Summary

### Catalog Publish (BPP → Discovr)
1. BPP sends `catalog/publish` to Publish Job
2. Published to Kafka topic
3. Consumer parses, validates, persists to **PostgreSQL** and indexes in **Elasticsearch**

### Discover (BAP → Discovr → BAP)
1. BAP sends `discover` request to Discover Job
2. Request validated (schema + Beckn HTTP signature) → published to Kafka → **ACK** returned immediately
3. Consumer picks up request asynchronously:
   - **PostgreSQL/PostGIS** for spatial queries (location-based)
   - **Elasticsearch** for text/keyword search
   - **NLWeb** for natural language search (optional)
4. Results pass through **CatalogPipeline** (schema filter → dedup → prune empty)
5. **ResponseProcessor** assembles `on_discover` response → publishes to Kafka response topic
6. **Response Dispatcher** consumes, signs with Beckn HTTP Signature, POSTs callback to BAP

### Tech Stack
- **Java 17 / Spring Boot** — all three jobs
- **Apache Kafka** — async decoupling between all stages
- **PostgreSQL + PostGIS** — catalog persistence + spatial queries
- **Elasticsearch** — full-text search + attribute filtering
