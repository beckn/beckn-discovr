# Beckn DISCOVR — Discovery Service

Welcome to the documentation for **Beckn DISCOVR** — the Discovery Service (DS) of the Beckn ecosystem. It is a high-performance, real-time discovery engine that enables consumers to find resources across all published catalogues in Beckn-enabled networks.

This guide is intended for Consumer Node (CN) developers, consumer application builders, network facilitators, and anyone building search experiences on top of Beckn catalogues.

## What is Beckn DISCOVR?

Beckn DISCOVR is the query-time search and discovery layer for Beckn ecosystems.

While Beckn CATALG (CS) acts as the source of truth for catalogue publishing and validation, DISCOVR (DS) is purpose-built for **real-time search** — it indexes catalogue data from CATALG and exposes it through fast, flexible query APIs that power consumer-facing search experiences.

DISCOVR receives catalogue updates from CATALG, indexes them for search, and serves discovery requests from Consumer Nodes (CNs) and consumer applications with sub-second response times.

## Why Do You Need Beckn DISCOVR?

Without a dedicated discovery layer, consumer applications face:

* Querying raw catalogue data directly — slow, unstructured, and unscalable
* Building custom search indexes per application — duplicated effort, inconsistent results
* No support for natural language, spatial, or attribute-based search across providers
* No unified view of resources across multiple catalogues and networks

Beckn DISCOVR solves this by offering:

* **One search endpoint** across all catalogues published to the network
* **Multiple search modes** — natural language, keyword, spatial (location-based), and JSONPath attribute filtering
* **Real-time indexing** — catalogue changes are indexed and searchable within seconds
* **Provider-agnostic results** — consumers see resources from all providers, with offers from multiple retailers in one response

## Key Capabilities

* **Natural language search** — ask questions like "strong Assam tea for morning chai" or "premium coffee under 500 rupees"
* **Keyword search** — find resources by name, description, brand, or any indexed attribute
* **Spatial search** — find resources available near a location using GeoJSON coordinates and a distance radius
* **Attribute filtering** — query resources and offers using JSONPath expressions for fine-grained filtering
* **Combined queries** — mix text search with spatial constraints in a single request
* **Schema-aware results** — responses include full resource attributes, ratings, offers, and provider details

## Where to Next

* [Getting Started](setup/getting-started.md) — connect as a consumer and run your first discover request
* [Deployment Prerequisites](setup/deployment-prerequisites.md) — what you need to provision to run DISCOVR
* [Discover Overview](discover/overview.md) — how discovery works end to end
* [Usage](discover/usage.md) and [Examples](discover/examples.md) — request/response formats and worked examples
