# Beckn Discovr — Architecture

```mermaid
graph LR
    subgraph External
        BAP["Application\n(BAP)"]
        BPP["Provider\n(BPP)"]
    end

    subgraph "Beckn Discovr"
        Publish["Catalog\nPublish"]
        Discover["Catalog\nDiscover"]
        Dispatcher["Response\nDispatcher"]
    end

    subgraph Infrastructure
        MQ["Message Queue"]
        DB["Database"]
        Search["Search Engine"]
    end

    BPP -->|"publish catalog"| Publish
    Publish --> MQ
    MQ --> Publish
    Publish -->|"store"| DB
    Publish -->|"index"| Search

    BAP -->|"discover"| Discover
    Discover --> MQ
    MQ --> Discover
    Discover -->|"spatial query"| DB
    Discover -->|"text search"| Search
    Discover --> MQ

    MQ --> Dispatcher
    Dispatcher -->|"callback\n(on_discover)"| BAP

    style BAP fill:#4A90D9,color:#fff,stroke:#3A7BC8
    style BPP fill:#4A90D9,color:#fff,stroke:#3A7BC8
    style Publish fill:#FFF3E0,stroke:#E8A838
    style Discover fill:#E6F3FF,stroke:#4A90D9
    style Dispatcher fill:#E8F5E9,stroke:#4CAF50
    style MQ fill:#E8A838,color:#fff,stroke:#D4942F
    style DB fill:#336791,color:#fff,stroke:#2A5578
    style Search fill:#00BFB3,color:#fff,stroke:#00A89E
```

## How it works

### Publishing a Catalog
A **Provider (BPP)** publishes its catalog to Discovr. The data is stored in a **Database** and indexed in a **Search Engine** for fast lookups.

### Discovering a Catalog
An **Application (BAP)** sends a discover request. Discovr searches using spatial queries (Database) and text search (Search Engine), then sends the results back to the application via an asynchronous callback.

All communication between services is decoupled through a **Message Queue**.
