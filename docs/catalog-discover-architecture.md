# Beckn Catalog & Discover — Complete Architecture

## The Big Picture

```mermaid
flowchart TB
    subgraph NETWORK["Beckn Network: openretail.org/electronics-india"]

        subgraph BPPS["BPPs (Sellers)"]
            CROMA["Croma<br/>BPP + ONIX"]
            AMAZON["Amazon<br/>BPP + ONIX"]
            SANG["Sangeetha<br/>BPP + ONIX"]
        end

        subgraph CATALG["Beckn Catalg (Catalog Management)"]
            PUBAPI["Publish API<br/>POST /catalog/publish"]
            SUBAPI["Subscription API<br/>POST /catalog/subscription"]
            INDEXER["Catalog Indexer"]
            CATDB[("Catalg DB<br/>(catalog master)")]
        end

        subgraph DISCOVR["Beckn Discovr (Discovery Engine)"]
            subgraph PUBLISH_JOB["Catalog Publish Job :8085"]
                PUSH["POST /catalog/push"]
                PUBCONSUMER["Consumer<br/>Parse → Validate → Persist"]
            end

            subgraph DISCOVER_JOB["Catalog Discover Job :8082"]
                DISCAPI["POST /beckn/discover"]
                DISCONSUMER["Discovery Consumer"]
                QE["Query Engine"]
                PIPELINE["Catalog Pipeline<br/>Filter → Dedup → Prune"]
                RESP["Response Processor"]
            end

            subgraph DISPATCHER["Response Dispatcher"]
                LISTENER["Event Listener"]
                SIGNER["Signature Service"]
            end
        end

        subgraph INFRA["Infrastructure"]
            KAFKA["Apache Kafka"]
            PG[("PostgreSQL<br/>+ PostGIS")]
            ES[("Elasticsearch")]
        end

        subgraph BAPS["BAPs (Apps)"]
            PRICE["PriceHunt<br/>BAP + ONIX"]
            PAYTM["Paytm<br/>BAP + ONIX"]
        end

    end

    subgraph DEDI["DeDi Global (Trust Layer)"]
        REG["Network Registry<br/>Public Keys & Subscriber Info"]
    end

    %% Publish flow
    CROMA -->|"1. catalog/publish"| PUBAPI
    AMAZON -->|"1. catalog/publish"| PUBAPI
    SANG -->|"1. catalog/publish"| PUBAPI

    PUBAPI --> INDEXER
    INDEXER --> CATDB
    INDEXER -->|"push indexed data"| PUSH

    PUSH -->|"to Kafka"| KAFKA
    KAFKA -->|"consume"| PUBCONSUMER
    PUBCONSUMER -->|"index"| ES
    PUBCONSUMER -->|"persist"| PG

    %% Discover flow
    PRICE -->|"5. discover"| DISCAPI
    PAYTM -->|"5. discover"| DISCAPI
    DISCAPI -->|"to Kafka"| KAFKA
    KAFKA -->|"consume"| DISCONSUMER
    DISCONSUMER --> QE
    QE -->|"spatial"| PG
    QE -->|"text search"| ES
    QE --> PIPELINE
    PIPELINE --> RESP
    RESP -->|"to Kafka"| KAFKA

    %% Response dispatch
    KAFKA -->|"consume"| LISTENER
    LISTENER --> SIGNER
    SIGNER -->|"6. on_discover<br/>(signed)"| PRICE
    SIGNER -->|"6. on_discover<br/>(signed)"| PAYTM

    %% Trust layer
    SIGNER -.->|"lookup public key"| REG
    DISCAPI -.->|"verify signature"| REG

    style BPPS fill:#e3f2fd,stroke:#1565c0
    style BAPS fill:#e0f7fa,stroke:#00838f
    style CATALG fill:#fff3e0,stroke:#e65100
    style DISCOVR fill:#e8f5e9,stroke:#2e7d32
    style INFRA fill:#f5f5f5,stroke:#616161
    style DEDI fill:#fce4ec,stroke:#c62828
```

---

## Flow 1: Subscription (BPP registers with Catalg)

**"Hey Catalg, I want to publish catalogs to the network"**

```mermaid
sequenceDiagram
    participant BPP as Croma (BPP)
    participant ONIX as Croma's ONIX
    participant CATALG as Beckn Catalg
    participant DEDI as DeDi Global

    Note over BPP,DEDI: One-time setup — BPP subscribes to Catalg

    BPP->>ONIX: I want to publish catalogs
    ONIX->>ONIX: Sign request with private key

    ONIX->>CATALG: POST /catalog/subscription
    Note right of ONIX: action: "catalog/subscription"<br/>bppId: croma.com<br/>networkId: openretail.org/electronics-india<br/>callback_url: https://croma.com/bpp/beckn

    CATALG->>DEDI: Lookup croma.com public key
    DEDI-->>CATALG: Public key for croma.com

    CATALG->>CATALG: Verify signature ✅
    CATALG->>CATALG: Register subscription

    CATALG-->>ONIX: ACK {"status": "ACK"}

    CATALG->>ONIX: POST callback → on_subscription
    Note right of CATALG: action: "catalog/on_subscription"<br/>status: confirmed
    ONIX->>BPP: Subscription confirmed ✅

    Note over BPP,DEDI: Croma can now publish catalogs
```

---

## Flow 2: Catalog Publish (BPP pushes products)

**"Here are my products — iPhones, Samsung phones, etc."**

```mermaid
sequenceDiagram
    participant BPP as Croma (BPP)
    participant ONIX as Croma's ONIX
    participant CATALG as Beckn Catalg
    participant KAFKA as Kafka
    participant PUBJOB as Catalog Publish Job
    participant PG as PostgreSQL
    participant ES as Elasticsearch

    Note over BPP,ES: Croma publishes iPhone 16 to the network

    BPP->>ONIX: Publish catalog
    ONIX->>ONIX: Sign with private key

    ONIX->>CATALG: POST /catalog/publish
    Note right of ONIX: action: "catalog/publish"<br/>bppId: croma.com<br/>networkId: openretail.org/electronics-india<br/>catalogs: [{<br/>  descriptor: {name: "Croma Electronics"},<br/>  resources: [{<br/>    descriptor: {name: "iPhone 16 128GB"},<br/>    resourceAttributes: {<br/>      @context: "schema.org",<br/>      @type: "Product",<br/>      brand: "Apple",<br/>      price: 79900<br/>    },<br/>    availableAt: {location: {gps: "12.97,77.59"}}<br/>  }],<br/>  offers: [{<br/>    descriptor: {name: "AppleCare Bundle"},<br/>    resourceIds: ["iphone-16-128"],<br/>    offerAttributes: {bundlePrice: 86899}<br/>  }]<br/>}]

    CATALG->>CATALG: Validate schema ✅
    CATALG-->>ONIX: ACK {"status": "ACK"}
    ONIX-->>BPP: Published ✅

    CATALG->>CATALG: Index catalog

    CATALG->>PUBJOB: POST /catalog/push (indexed data)
    PUBJOB->>KAFKA: Publish to topic

    KAFKA->>PUBJOB: Consumer picks up
    PUBJOB->>PUBJOB: Parse → Validate → Persist

    par Store in parallel
        PUBJOB->>PG: Persist catalog + spatial data (PostGIS)
        PUBJOB->>ES: Index for text search
    end

    Note over PG,ES: iPhone 16 from Croma is now<br/>searchable in Discovr

    CATALG->>ONIX: POST callback → on_publish
    Note right of CATALG: action: "catalog/on_publish"<br/>status: indexed
```

---

## Flow 3: Discover (BAP searches for products)

**"User wants iPhone 16 near Koramangala"**

```mermaid
sequenceDiagram
    participant USER as Rahul
    participant BAP as PriceHunt App
    participant BONIX as PriceHunt ONIX
    participant DISC as Discover Job
    participant KAFKA as Kafka
    participant PG as PostgreSQL
    participant ES as Elasticsearch
    participant DISP as Response Dispatcher
    participant DEDI as DeDi Global

    Note over USER,DEDI: Rahul searches for iPhone 16

    USER->>BAP: Search "iPhone 16"
    BAP->>BONIX: Create discover request

    BONIX->>BONIX: Sign with PriceHunt private key

    BONIX->>DISC: POST /beckn/discover
    Note right of BONIX: action: "discover"<br/>bapId: pricehunt.in<br/>bapUri: https://pricehunt.in/bap/beckn<br/>networkId: openretail.org/electronics-india<br/>intent: {<br/>  descriptor: {name: "iPhone 16"},<br/>  availableAt: {location: {gps: "12.97,77.59"}}<br/>}

    DISC->>DEDI: Lookup pricehunt.in public key
    DEDI-->>DISC: Public key for pricehunt.in
    DISC->>DISC: Verify signature ✅
    DISC->>DISC: Validate schema ✅

    DISC->>KAFKA: Publish to request topic
    DISC-->>BONIX: ACK {"status": "ACK"}

    Note over DISC,ES: Async processing begins

    KAFKA->>DISC: Consumer picks up request

    par Query in parallel
        DISC->>PG: Spatial query — sellers near 12.97,77.59
        DISC->>ES: Text search — "iPhone 16"
    end

    PG-->>DISC: Croma (1.2km), Sangeetha (800m)
    ES-->>DISC: iPhone 16 matches from Croma, Amazon, Sangeetha

    DISC->>DISC: CatalogPipeline
    Note right of DISC: 1. Schema context filter<br/>2. Dedup offers<br/>3. Filter resources by offer refs<br/>4. Filter offers by resource ids<br/>5. Remove empty catalogs

    DISC->>DISC: ResponseProcessor
    Note right of DISC: Build on_discover response<br/>action: "on_discover"

    DISC->>KAFKA: Publish to response topic

    KAFKA->>DISP: Consumer picks up response

    DISP->>DISP: Sign with network key
    DISP->>BONIX: POST https://pricehunt.in/bap/beckn
    Note right of DISP: on_discover callback:<br/>catalogs: [<br/>  {Croma: iPhone 16 ₹79,900 + AppleCare offer},<br/>  {Amazon: iPhone 16 ₹77,900 + HDFC offer},<br/>  {Sangeetha: iPhone 16 ₹79,500 + free screen guard}<br/>]

    BONIX->>BONIX: Verify dispatcher signature ✅
    BONIX->>BAP: Return results

    BAP->>USER: Show all sellers and prices
```

---

## Flow 4: All Three Together (Timeline)

```mermaid
flowchart TD
    subgraph STEP1["Step 1: Subscribe (one-time)"]
        S1["Croma BPP"] -->|"catalog/subscription"| S2["Catalg"]
        S2 -->|"on_subscription: confirmed"| S1
        S3["Amazon BPP"] -->|"catalog/subscription"| S2
        S4["Sangeetha BPP"] -->|"catalog/subscription"| S2
    end

    subgraph STEP2["Step 2: Publish (ongoing)"]
        P1["Croma"] -->|"catalog/publish<br/>iPhone 16, Samsung S25..."| P2["Catalg"]
        P3["Amazon"] -->|"catalog/publish<br/>iPhone 16, OnePlus 13..."| P2
        P4["Sangeetha"] -->|"catalog/publish<br/>iPhone 16, Pixel 9..."| P2
        P2 -->|"push indexed data"| P5["Discovr Publish Job"]
        P5 -->|"index"| P6[("Elasticsearch")]
        P5 -->|"persist"| P7[("PostgreSQL")]
    end

    subgraph STEP3["Step 3: Discover (on-demand)"]
        D1["Rahul on PriceHunt"] -->|"discover: iPhone 16"| D2["Discovr Discover Job"]
        D2 -->|"query"| D3[("ES + PG")]
        D3 -->|"results"| D2
        D2 -->|"on_discover"| D4["Response Dispatcher"]
        D4 -->|"callback with results"| D5["PriceHunt App"]
        D5 -->|"show results"| D1
    end

    STEP1 --> STEP2
    STEP2 --> STEP3

    style STEP1 fill:#fff3e0,stroke:#e65100
    style STEP2 fill:#e3f2fd,stroke:#1565c0
    style STEP3 fill:#e8f5e9,stroke:#2e7d32
```

---

## Data Flow Summary

| Step | Action | From | To | What happens |
|------|--------|------|----|-------------|
| 1 | `catalog/subscription` | BPP → Catalg | One-time | BPP registers to publish catalogs |
| 2 | `catalog/on_subscription` | Catalg → BPP | Callback | Confirms subscription |
| 3 | `catalog/publish` | BPP → Catalg | Ongoing | BPP sends product catalogs |
| 4 | Internal push | Catalg → Discovr Publish Job | Internal | Indexed data pushed to Discovr |
| 5 | Persist + Index | Publish Job → PG + ES | Internal | Stored for spatial + text queries |
| 6 | `catalog/on_publish` | Catalg → BPP | Callback | Confirms publish success |
| 7 | `discover` | BAP → Discovr | On-demand | User searches for products |
| 8 | Query | Discovr → PG + ES | Internal | Spatial + text search across all catalogs |
| 9 | Pipeline | Internal | Internal | Filter, dedup, prune results |
| 10 | `on_discover` | Dispatcher → BAP | Callback | Signed results sent to BAP |

---

## Where Each System Lives

```mermaid
flowchart LR
    subgraph BPP_SERVER["Croma's Server"]
        BPP_APP["Croma Backend"]
        BPP_ONIX["ONIX<br/>(signs & verifies)"]
    end

    subgraph CATALG_SERVER["Catalg Server"]
        CATALG["Beckn Catalg<br/>(catalog management)"]
    end

    subgraph DISCOVR_SERVER["Discovr Server"]
        PUB["Publish Job"]
        DISC["Discover Job"]
        DISP["Response Dispatcher"]
        KF["Kafka"]
        PG["PostgreSQL"]
        ES["Elasticsearch"]
    end

    subgraph BAP_SERVER["PriceHunt's Server"]
        BAP_APP["PriceHunt Backend"]
        BAP_ONIX["ONIX<br/>(signs & verifies)"]
    end

    subgraph TRUST["DeDi Global"]
        REGISTRY["Public Keys<br/>& Subscriber Info"]
    end

    BPP_ONIX -->|"publish"| CATALG
    CATALG -->|"push"| PUB
    BAP_ONIX -->|"discover"| DISC
    DISP -->|"on_discover"| BAP_ONIX

    BPP_ONIX -.->|"register keys"| REGISTRY
    BAP_ONIX -.->|"register keys"| REGISTRY
    DISC -.->|"verify keys"| REGISTRY

    style BPP_SERVER fill:#e3f2fd,stroke:#1565c0
    style BAP_SERVER fill:#e0f7fa,stroke:#00838f
    style CATALG_SERVER fill:#fff3e0,stroke:#e65100
    style DISCOVR_SERVER fill:#e8f5e9,stroke:#2e7d32
    style TRUST fill:#fce4ec,stroke:#c62828
```
