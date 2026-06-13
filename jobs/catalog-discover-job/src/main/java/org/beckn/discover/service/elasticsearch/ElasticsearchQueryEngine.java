package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link QueryEngine} implementation that routes spatial queries to Elasticsearch
 * and filter queries to PostgreSQL.
 *
 * <p>Active when {@code discovery.spatial.engine=elasticsearch}. Decorated over
 * {@link PostgreSQLQueryEngine} — filter queries (Path B) always go to PostgreSQL;
 * spatial queries (Path C) use ES geo_shape on loc_* fields. Combined (Path A,
 * J+G) is always handled in {@link org.beckn.discover.service.DiscoveryService}
 * via {@link PostgreSQLQueryEngine#executeCombinedQuery} directly, not through
 * this engine.</p>
 *
 * <p>Additionally exposes {@link #fetchMatchingResourceIds} for step 1 of the
 * JSONPath+text query (cases 6 J+T and 7 J+G+T), returning a ranked list of
 * resource IDs only. This is a dedicated lexical-BM25 path that does not invoke
 * the configured {@link org.beckn.discover.service.engine.TextSearchEngine}
 * (semantic / NLWeb).</p>
 */
@Service
@Primary
@ConditionalOnProperty(name = "discovery.spatial.engine", havingValue = "elasticsearch")
public class ElasticsearchQueryEngine implements QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchQueryEngine.class);

    private final PostgreSQLQueryEngine     pgEngine;
    private final EsSpatialQueryBuilder     spatialBuilder;
    private final ElasticsearchClient       esClient;
    private final EsSearchAssembler         assembler;
    private final DiscoveryProperties       discoveryProperties;
    private final Optional<EmbeddingClient> embeddingClient;
    private final Optional<QueryEnricher>   queryEnricher;
    private final int                       knnCandidates;

    public ElasticsearchQueryEngine(PostgreSQLQueryEngine pgEngine,
                                    EsSpatialQueryBuilder spatialBuilder,
                                    ElasticsearchClient esClient,
                                    EsSearchAssembler assembler,
                                    DiscoveryProperties discoveryProperties,
                                    Optional<EmbeddingClient> embeddingClient,
                                    Optional<QueryEnricher> queryEnricher) {
        this.pgEngine        = pgEngine;
        this.spatialBuilder  = spatialBuilder;
        this.esClient        = esClient;
        this.assembler       = assembler;
        this.discoveryProperties = discoveryProperties;
        this.embeddingClient = embeddingClient;
        this.queryEnricher   = queryEnricher;
        this.knnCandidates   = Math.max(
                discoveryProperties.getTextSearch().getEmbeddingModel().getKnnCandidates(),
                discoveryProperties.getElasticsearch().getResultLimit());
    }

    /** Path B: always delegates to PostgreSQL filter query. */
    @Override
    public List<Catalog> executeFilterQuery(QueryRequest request) throws Exception {
        return pgEngine.executeFilterQuery(request);
    }

    /** Path C: ES geo_shape spatial query on loc_{key}.geo fields. */
    @Override
    @SuppressWarnings("unchecked")
    public List<Catalog> executeSpatialQuery(QueryRequest request) throws Exception {
        Instant start = Instant.now();
        log.debug("event={}", LogEvent.ES_ENGINE_SPATIAL_START);

        Optional<List<Query>> queriesOpt = spatialBuilder.buildGeoShapeQueries(request.spatial());
        if (queriesOpt.isEmpty()) {
            log.debug("event={} reason=no-valid-queries", LogEvent.ES_ENGINE_SPATIAL_SKIP);
            return List.of();
        }

        String alias = discoveryProperties.getElasticsearch().getAliasName();
        int limit    = discoveryProperties.getElasticsearch().getResultLimit();

        boolean hasText = request.hasTextSearch();
        List<Query> geoQueries = queriesOpt.get();

        // ── Semantic search + spatial: KNN with geo_shape as knn.filter ──────
        if (hasText && embeddingClient.isPresent()) {
            String text = request.textSearch();
            String enriched = queryEnricher.isPresent() ? queryEnricher.get().enrich(text) : text;
            Optional<List<Float>> vecOpt = embeddingClient.get().embed(enriched);
            if (vecOpt.isEmpty()) {
                log.warn("event={} reason=empty-vector", LogEvent.ES_ENGINE_SPATIAL_EMPTY_VECTOR);
                return List.of();
            }
            List<Float> vec = vecOpt.get();
            double minScore = discoveryProperties.getElasticsearch().getMinScore();
            List<Query> schemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(request);
            log.debug("event={} alias={} k={} numCandidates={} geoFilters={} schemaFilters={}",
                    LogEvent.ES_ENGINE_SPATIAL_REQUEST, alias, limit, knnCandidates, geoQueries.size(), schemaFilters.size());
            if (!schemaFilters.isEmpty()) {
                log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                        value("path", "spatial+semantic"),
                        value("schemaFilters", schemaFilters.size()));
            }
            try {
                SearchResponse<Map> response = esClient.search(s -> s
                        .index(alias)
                        .size(limit)
                        .minScore(minScore)
                        // H1: exclude large fields not used by EsSearchAssembler
                        .source(sf -> sf.filter(f -> f.excludes("full_text_blob", "resource_vector", "indexed_at")))
                        .trackTotalHits(t -> t.enabled(false))
                        .knn(k -> {
                            var kb = k.field("resource_vector")
                                    .queryVector(vec)
                                    .k(limit)
                                    .numCandidates(knnCandidates);
                            geoQueries.forEach(kb::filter);
                            schemaFilters.forEach(kb::filter);
                            return kb;
                        }), Map.class);

                List<Map<String, Object>> hits = response.hits().hits().stream()
                        .map(h -> (Map<String, Object>) h.source())
                        .filter(Objects::nonNull)
                        .toList();
                List<Catalog> catalogs = assembler.assemble(hits, request.transactionId());
                log.info("event={} catalogs={} durationMs={}",
                        LogEvent.ES_ENGINE_SPATIAL_DONE, catalogs.size(), elapsed(start));
                return catalogs;
            } catch (ElasticsearchException e) {
                return handleEsException(e, alias, request.transactionId());
            }
        }

        // ── BM25 text + spatial OR spatial only ───────────────────────────────
        // H4: geo-shape queries always go to bool.filter — no scoring contribution.
        // Text scoring mirrors ElasticsearchTextSearchEngine (Path D) so the same
        // minScore floor calibrates correctly for both paths:
        //   must:   match(full_text_blob, AND)      — gating + BM25 score
        //   should: multiMatch(scoringFields, ...)  — name/provider boost
        List<Query> spatialSchemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(request);
        double applyMinScore = 0.0;
        final Query textMustQuery;
        final Query textShouldQuery;

        if (hasText) {
            String text = request.textSearch();
            DiscoveryProperties.Elasticsearch esConfig = discoveryProperties.getElasticsearch();
            List<String> mmFields = esConfig.getMultiMatchFields();
            List<String> blobFields = mmFields.stream()
                    .filter(f -> f.startsWith("full_text_blob"))
                    .toList();
            List<String> scoringFields = mmFields.stream()
                    .filter(f -> !f.startsWith("full_text_blob"))
                    .toList();
            final double tieBreaker = esConfig.getTieBreaker();
            final String fuzziness  = esConfig.getFuzziness();

            // Mirror Path D's text-query structure (see ElasticsearchTextSearchEngine):
            //   must:   match(full_text_blob)              — required match + BM25 score
            //   should: multiMatch(scoringFields, BestFields) — name/provider boost
            // fuzziness is read from config so operators can tighten/loosen typo tolerance.
            textMustQuery = !blobFields.isEmpty()
                    ? Query.of(q -> q.match(m -> m
                            .field(blobFields.get(0))
                            .query(text)
                            .operator(Operator.And)
                            .fuzziness(fuzziness)))
                    : null;
            textShouldQuery = !scoringFields.isEmpty()
                    ? Query.of(q -> q.multiMatch(mm -> mm
                            .query(text)
                            .fields(scoringFields)
                            .type(TextQueryType.BestFields)
                            .tieBreaker(tieBreaker)
                            .fuzziness(fuzziness)))
                    : null;
            applyMinScore = esConfig.getMinScore();
            log.debug("event={} schemaFilters={}", LogEvent.ES_ENGINE_SPATIAL_REQUEST, spatialSchemaFilters.size());
        } else {
            textMustQuery = null;
            textShouldQuery = null;
            log.debug("event={} schemaFilters={}", LogEvent.ES_ENGINE_SPATIAL_REQUEST, spatialSchemaFilters.size());
        }

        if (!spatialSchemaFilters.isEmpty()) {
            log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                    value("path", hasText ? "spatial+text" : "spatial"),
                    value("schemaFilters", spatialSchemaFilters.size()));
        }

        final List<Query> finalGeoFilters = geoQueries;
        final List<Query> finalSchemaFilters = spatialSchemaFilters;
        final double finalMinScore = applyMinScore;

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                var b = s.index(alias)
                        .size(limit)
                        // H1: exclude large fields not used by EsSearchAssembler
                        .source(sf -> sf.filter(f -> f.excludes("full_text_blob", "resource_vector", "indexed_at")))
                        .trackTotalHits(t -> t.enabled(false))
                        .query(q -> q.bool(bq -> {
                            // geo-shape queries in filter — no scoring, cache-friendly
                            finalGeoFilters.forEach(bq::filter);
                            finalSchemaFilters.forEach(bq::filter);
                            // text clauses contribute to score (mirrors Path D)
                            if (textMustQuery != null)   bq.must(textMustQuery);
                            if (textShouldQuery != null) bq.should(textShouldQuery);
                            return bq;
                        }));
                return finalMinScore > 0 ? b.minScore(finalMinScore) : b;
            }, Map.class);

            List<Map<String, Object>> hits = response.hits().hits().stream()
                    .map(h -> (Map<String, Object>) h.source())
                    .filter(Objects::nonNull)
                    .toList();

            List<Catalog> catalogs = assembler.assemble(hits, request.transactionId());
            log.info("event={} catalogs={} durationMs={}",
                    LogEvent.ES_ENGINE_SPATIAL_DONE, catalogs.size(), elapsed(start));
            return catalogs;

        } catch (ElasticsearchException e) {
            return handleEsException(e, alias, request.transactionId());
        }
    }

    /**
     * Path A is always handled by {@link PostgreSQLQueryEngine#executeCombinedQuery}
     * directly in {@code DiscoveryService}, not by this engine. This method exists
     * to satisfy the {@link QueryEngine} interface and always returns
     * {@code Optional.empty()} — the orchestrator never invokes it.
     */
    @Override
    public Optional<List<Catalog>> executeCombinedQuery(QueryRequest request) throws Exception {
        return Optional.empty();
    }

    /**
     * Step 1 of the JSONPath+text search query (cases 6 J+T and 7 J+G+T):
     * runs a lexical (BM25) ES query for the given text [+ geo] and returns
     * only the {@code resource_id} field from the top-{@code size} hits, in
     * ES relevance order.
     *
     * <p>Uses the same bool query structure as {@link #executeSpatialQuery} for the
     * text and geo clauses. Sets {@code _source} to include only {@code resource_id}
     * to minimise network overhead.</p>
     *
     * <p><b>This is a dedicated lexical-BM25 path</b> — independent of
     * {@code TextSearchEngine.search()}. Semantic search (els-semantic-search) and
     * NLWeb dispatch happen only on Path D (case 3 T-only) via
     * {@link TextSearchEngine}; cases 6 and 7 always use lexical BM25 here for
     * fast ID lookup. {@code TextSearchEngine} implementations are not touched
     * by this method.</p>
     *
     * <p>Returns an empty list only for benign conditions (no hits, missing index,
     * unindexed targets path). Any other failure — connection error, malformed
     * query, serialization error — is propagated to the caller so infrastructure
     * failures are not silently degraded to "no results."</p>
     *
     * @param request the active query request (must have hasTextSearch() == true)
     * @param size    maximum number of resource IDs to return
     * @return list of resource IDs in ES relevance order; empty when no hits
     * @throws Exception when ES is unreachable, the query is malformed, or any
     *                   non-benign failure occurs
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchMatchingResourceIds(QueryRequest request, int size) throws Exception {
        Instant start = Instant.now();
        String alias = discoveryProperties.getElasticsearch().getAliasName();
        DiscoveryProperties.Elasticsearch esConfig = discoveryProperties.getElasticsearch();

        // Build text query clauses — mirrors executeSpatialQuery BM25 path.
        String text = request.textSearch();
        List<String> mmFields     = esConfig.getMultiMatchFields();
        List<String> blobFields   = mmFields.stream().filter(f -> f.startsWith("full_text_blob")).toList();
        List<String> scoringFields = mmFields.stream().filter(f -> !f.startsWith("full_text_blob")).toList();
        final double tieBreaker   = esConfig.getTieBreaker();
        final String fuzziness    = esConfig.getFuzziness();

        final Query textMustQuery = !blobFields.isEmpty()
                ? Query.of(q -> q.match(m -> m
                        .field(blobFields.get(0))
                        .query(text)
                        .operator(Operator.And)
                        .fuzziness(fuzziness)))
                : null;
        final Query textShouldQuery = !scoringFields.isEmpty()
                ? Query.of(q -> q.multiMatch(mm -> mm
                        .query(text)
                        .fields(scoringFields)
                        .type(TextQueryType.BestFields)
                        .tieBreaker(tieBreaker)
                        .fuzziness(fuzziness)))
                : null;
        final double applyMinScore = esConfig.getMinScore();

        // Build optional geo shape filter (case 7 — present when request has spatial).
        List<Query> geoFilters = List.of();
        if (request.hasSpatial()) {
            Optional<List<Query>> queriesOpt = spatialBuilder.buildGeoShapeQueries(request.spatial());
            geoFilters = queriesOpt.orElse(List.of());
        }

        final List<Query> schemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(request);
        final List<Query> finalGeoFilters = geoFilters;
        final int effectiveSize = size;

        log.debug("event={} chain alias={} size={} geo={} schema={}",
                LogEvent.CHAIN_ES_CANDIDATES_FETCHED, alias, effectiveSize,
                finalGeoFilters.size(), schemaFilters.size());

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                var b = s.index(alias)
                        .size(effectiveSize)
                        // Only fetch resource_id — minimise network overhead for chain step 1.
                        .source(sf -> sf.filter(f -> f.includes("resource_id")))
                        .trackTotalHits(t -> t.enabled(false))
                        .query(q -> q.bool(bq -> {
                            finalGeoFilters.forEach(bq::filter);
                            schemaFilters.forEach(bq::filter);
                            if (textMustQuery != null)   bq.must(textMustQuery);
                            if (textShouldQuery != null) bq.should(textShouldQuery);
                            return bq;
                        }));
                return applyMinScore > 0 ? b.minScore(applyMinScore) : b;
            }, Map.class);

            List<String> ids = response.hits().hits().stream()
                    .map(h -> (Map<String, Object>) h.source())
                    .filter(Objects::nonNull)
                    .map(doc -> {
                        Object v = doc.get("resource_id");
                        return v instanceof String s && !s.isBlank() ? s : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();

            log.info("event={} ids={} durationMs={}",
                    LogEvent.CHAIN_ES_CANDIDATES_FETCHED, ids.size(), elapsed(start));
            return ids;

        } catch (ElasticsearchException e) {
            // Benign: missing index / unindexed targets path → empty result.
            // Any other ES-level error is re-thrown to the caller.
            if ("index_not_found_exception".equals(e.error().type())) {
                log.warn("event={} alias={}", LogEvent.ES_ENGINE_SPATIAL_INDEX_NOT_FOUND, alias);
                return List.of();
            }
            if ("search_phase_execution_exception".equals(e.error().type())
                    && e.error().rootCause().stream().anyMatch(rc ->
                            rc.reason() != null && rc.reason().contains("failed to find type for field"))) {
                String fieldHint = e.error().rootCause().get(0).reason();
                log.warn("event={} reason=targets-path-not-indexed hint='{}'",
                        LogEvent.ES_ENGINE_SPATIAL_UNKNOWN_FIELD, fieldHint);
                return List.of();
            }
            log.error("event={} error={}", LogEvent.ES_SEARCH_FAILED, e.getMessage(), e);
            throw e;
        }
    }

    private List<Catalog> handleEsException(ElasticsearchException e, String alias, String transactionId) {
        if ("index_not_found_exception".equals(e.error().type())) {
            log.warn("event={} alias={}", LogEvent.ES_ENGINE_SPATIAL_INDEX_NOT_FOUND, alias);
            return List.of();
        }
        if ("search_phase_execution_exception".equals(e.error().type())
                && e.error().rootCause().stream().anyMatch(rc ->
                        rc.reason() != null && rc.reason().contains("failed to find type for field"))) {
            String fieldHint = e.error().rootCause().get(0).reason();
            log.warn("event={} reason=targets-path-not-indexed hint='{}'",
                    LogEvent.ES_ENGINE_SPATIAL_UNKNOWN_FIELD, fieldHint);
            return List.of();
        }
        throw e;
    }

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }
}
