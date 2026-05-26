# ADR-0008: Pluggable Text Search Engine via Configuration

**Date**: 2026-05-26
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Text search requirements vary significantly by deployment: some networks need BM25 keyword search (fast, no AI infrastructure), others need semantic vector search (better recall for natural language), and some operators want to integrate NLWeb-style AI search. Hardcoding a single text search implementation would prevent operators from choosing the right trade-off for their deployment without modifying source code. Simultaneously, the two non-trivial backends (ES semantic and NLWeb) have dependencies (embedding models, external APIs) that should not be initialized when not configured.

## Decision

The text search engine is selected at startup via `discovery.text-search.engine` configuration:

- **`native-els`** (default) — BM25 multi-match across `full_text_blob`, `resource_name`, `catalog_name`, `resource_provider_name`, `resource_rating_review_text` with configurable field boosts and relative score threshold
- **`els-semantic-search`** — vector KNN search using an OpenAI-compatible embedding model, with optional LLM query enrichment (synonym expansion via a configurable system prompt) before embedding
- **`nlweb`** — delegates to an external NLWeb API endpoint

All three implement the `TextSearchEngine` interface. The ES and NLWeb beans are conditionally registered (`@ConditionalOnProperty`) and only initialized when the matching engine is selected.

## Alternatives Considered

### Alternative 1: Hardcode Elasticsearch BM25 as the only text search
- **Pros**: Simpler code, fewer configuration knobs, no unused code paths
- **Cons**: Locks operators into BM25; semantic search is a near-term requirement for Beckn AI discovery use cases
- **Why not**: NLWeb integration was an explicit roadmap item at the time this architecture was designed

### Alternative 2: Always initialize all engines and select at query time
- **Pros**: Hot-switching between engines without restart
- **Cons**: Initializes embedding model connections and NLWeb HTTP clients even when not in use; creates unnecessary resource overhead
- **Why not**: Each engine has its own connection pools and retry configurations; unconditional initialization wastes resources and creates misleading startup errors when external services (Ollama, OpenAI) are not configured

## Consequences

### Positive
- Operators choose the search quality/infrastructure trade-off for their deployment without code changes
- The embedding model base URL, API key, and model name are all configurable — supports Ollama (local), OpenAI, Azure OpenAI, or any OpenAI-compatible provider
- LLM query enrichment is independently toggleable within `els-semantic-search` mode
- Changing the embedding model is documented as requiring ES index recreation (vectors must be re-indexed)

### Negative
- Three code paths to maintain; integration tests for each engine require different infrastructure (Ollama + vector index for semantic, NLWeb mock for nlweb)
- The embedding model in `catalog-publish-job` and `catalog-discover-job` must always be kept in sync — model drift between publish and query produces incorrect KNN results

### Risks
- An operator misconfigures the embedding model name (publish uses model A, discover uses model B) — vector distances become meaningless. Documented requirement: embedding model config must match between publish and discover jobs.
