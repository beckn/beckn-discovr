package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
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
 * spatial queries (Path C) use ES geo_shape on loc_* fields; combined (Path A)
 * always returns {@code Optional.empty()} to trigger the parallel PG ∥ ES fallback
 * in {@code DiscoveryService}.</p>
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

        // ── BM25 text + spatial OR spatial only: geo_shape in bool.must ──────
        List<Query> mustQueries = new ArrayList<>(geoQueries);
        List<Query> spatialSchemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(request);
        double applyMinScore = 0.0;

        if (hasText) {
            String text = request.textSearch();
            mustQueries.add(Query.of(q -> q.multiMatch(mm -> mm
                    .query(text)
                    .fields("full_text_blob", "resource_name^2")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO"))));
            applyMinScore = discoveryProperties.getElasticsearch().getMinScore();
            log.debug("event={} schemaFilters={}", LogEvent.ES_ENGINE_SPATIAL_REQUEST, spatialSchemaFilters.size());
        } else {
            log.debug("event={} schemaFilters={}", LogEvent.ES_ENGINE_SPATIAL_REQUEST, spatialSchemaFilters.size());
        }

        if (!spatialSchemaFilters.isEmpty()) {
            log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                    value("path", hasText ? "spatial+text" : "spatial"),
                    value("schemaFilters", spatialSchemaFilters.size()));
        }

        final List<Query> finalQueries = mustQueries;
        final List<Query> finalSchemaFilters = spatialSchemaFilters;
        final double finalMinScore = applyMinScore;

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                var b = s.index(alias)
                        .size(limit)
                        .trackTotalHits(t -> t.enabled(false))
                        .query(q -> q.bool(bq -> {
                            finalQueries.forEach(bq::must);
                            finalSchemaFilters.forEach(bq::filter);
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
     * Path A: always returns {@code Optional.empty()} to force the parallel
     * PG-filter ∥ ES-spatial fallback in {@code DiscoveryService}.
     */
    @Override
    public Optional<List<Catalog>> executeCombinedQuery(QueryRequest request) throws Exception {
        return Optional.empty();
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
