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
    private final DiscoveryProperties       props;
    private final Optional<EmbeddingClient> embeddingClient;
    private final Optional<QueryEnricher>   queryEnricher;
    private final int                       knnCandidates;

    public ElasticsearchQueryEngine(PostgreSQLQueryEngine pgEngine,
                                    EsSpatialQueryBuilder spatialBuilder,
                                    ElasticsearchClient esClient,
                                    EsSearchAssembler assembler,
                                    DiscoveryProperties props,
                                    Optional<EmbeddingClient> embeddingClient,
                                    Optional<QueryEnricher> queryEnricher) {
        this.pgEngine        = pgEngine;
        this.spatialBuilder  = spatialBuilder;
        this.esClient        = esClient;
        this.assembler       = assembler;
        this.props           = props;
        this.embeddingClient = embeddingClient;
        this.queryEnricher   = queryEnricher;
        this.knnCandidates   = Math.max(
                props.getTextSearch().getEmbeddingModel().getKnnCandidates(),
                props.getElasticsearch().getResultLimit());
    }

    /** Path B: always delegates to PostgreSQL filter query. */
    @Override
    public List<Catalog> executeFilterQuery(QueryRequest req) throws Exception {
        return pgEngine.executeFilterQuery(req);
    }

    /** Path C: ES geo_shape spatial query on loc_{key}.geo fields. */
    @Override
    @SuppressWarnings("unchecked")
    public List<Catalog> executeSpatialQuery(QueryRequest req) throws Exception {
        Instant start = Instant.now();
        log.debug("es.engine.spatial.start transactionId={}", req.transactionId());

        Optional<List<Query>> queriesOpt = spatialBuilder.buildGeoShapeQueries(req.spatial());
        if (queriesOpt.isEmpty()) {
            log.info("es.engine.spatial.skip reason=no-valid-queries transactionId={}", req.transactionId());
            return List.of();
        }

        String alias = props.getElasticsearch().getAliasName();
        int limit    = props.getElasticsearch().getResultLimit();

        boolean hasText = req.hasTextSearch();
        List<Query> geoQueries = queriesOpt.get();

        // ── Semantic search + spatial: KNN with geo_shape as knn.filter ──────
        if (hasText && embeddingClient.isPresent()) {
            String text = req.textSearch();
            String enriched = queryEnricher.isPresent() ? queryEnricher.get().enrich(text) : text;
            Optional<List<Float>> vecOpt = embeddingClient.get().embed(enriched);
            if (vecOpt.isEmpty()) {
                log.warn("es.engine.spatial+semantic.empty-vector transactionId={} — returning empty", req.transactionId());
                return List.of();
            }
            List<Float> vec = vecOpt.get();
            double minScore = props.getElasticsearch().getMinScore();
            List<Query> schemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(req);
            log.info("es.engine.spatial+semantic.request txId={} alias={} k={} numCandidates={} geoFilters={} schemaFilters={}",
                    req.transactionId(), alias, limit, knnCandidates, geoQueries.size(), schemaFilters.size());
            if (!schemaFilters.isEmpty()) {
                log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                        value("path", "spatial+semantic"),
                        value("schemaFilters", schemaFilters.size()),
                        value("transactionId", req.transactionId()));
            }
            try {
                SearchResponse<Map> response = esClient.search(s -> s
                        .index(alias)
                        .size(limit)
                        .minScore(minScore)
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
                List<Catalog> catalogs = assembler.assemble(hits, req.transactionId());
                log.info("es.engine.spatial+semantic.done catalogs={} durationMs={} transactionId={}",
                        catalogs.size(), elapsed(start), req.transactionId());
                return catalogs;
            } catch (ElasticsearchException e) {
                return handleEsException(e, alias, req.transactionId());
            }
        }

        // ── BM25 text + spatial OR spatial only: geo_shape in bool.must ──────
        List<Query> mustQueries = new ArrayList<>(geoQueries);
        List<Query> spatialSchemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(req);
        double applyMinScore = 0.0;

        if (hasText) {
            String text = req.textSearch();
            mustQueries.add(Query.of(q -> q.multiMatch(mm -> mm
                    .query(text)
                    .fields("full_text_blob", "resource_name^2")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO"))));
            applyMinScore = props.getElasticsearch().getMinScore();
            log.info("es.spatial+text.request txId={} schemaFilters={} {}", req.transactionId(),
                    spatialSchemaFilters.size(),
                    spatialBuilder.buildCombinedRequestJson(req.spatial(), text, alias, limit, applyMinScore));
        } else {
            log.info("es.spatial.request txId={} schemaFilters={} {}", req.transactionId(),
                    spatialSchemaFilters.size(),
                    spatialBuilder.buildRequestJson(req.spatial(), alias, limit));
        }

        if (!spatialSchemaFilters.isEmpty()) {
            log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                    value("path", hasText ? "spatial+text" : "spatial"),
                    value("schemaFilters", spatialSchemaFilters.size()),
                    value("transactionId", req.transactionId()));
        }

        final List<Query> finalQueries = mustQueries;
        final List<Query> finalSchemaFilters = spatialSchemaFilters;
        final double finalMinScore = applyMinScore;

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                var b = s.index(alias)
                        .size(limit)
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

            List<Catalog> catalogs = assembler.assemble(hits, req.transactionId());
            log.info("es.engine.spatial{}.done catalogs={} durationMs={} transactionId={}",
                    hasText ? "+text" : "", catalogs.size(), elapsed(start), req.transactionId());
            return catalogs;

        } catch (ElasticsearchException e) {
            return handleEsException(e, alias, req.transactionId());
        }
    }

    /**
     * Path A: always returns {@code Optional.empty()} to force the parallel
     * PG-filter ∥ ES-spatial fallback in {@code DiscoveryService}.
     */
    @Override
    public Optional<List<Catalog>> executeCombinedQuery(QueryRequest req) throws Exception {
        return Optional.empty();
    }

    private List<Catalog> handleEsException(ElasticsearchException e, String alias, String transactionId) {
        if ("index_not_found_exception".equals(e.error().type())) {
            log.warn("es.engine.spatial.index-not-found alias={} transactionId={}", alias, transactionId);
            return List.of();
        }
        if ("search_phase_execution_exception".equals(e.error().type())
                && e.error().rootCause().stream().anyMatch(rc ->
                        rc.reason() != null && rc.reason().contains("failed to find type for field"))) {
            String fieldHint = e.error().rootCause().get(0).reason();
            log.warn("es.engine.spatial.unknown-field hint='{}' transactionId={} — targets path not indexed; returning empty",
                    fieldHint, transactionId);
            return List.of();
        }
        throw e;
    }

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }
}
