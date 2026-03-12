package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

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

    private final PostgreSQLQueryEngine pgEngine;
    private final EsSpatialQueryBuilder spatialBuilder;
    private final ElasticsearchClient   esClient;
    private final EsSearchAssembler     assembler;
    private final DiscoveryProperties   props;

    public ElasticsearchQueryEngine(PostgreSQLQueryEngine pgEngine,
                                    EsSpatialQueryBuilder spatialBuilder,
                                    ElasticsearchClient esClient,
                                    EsSearchAssembler assembler,
                                    DiscoveryProperties props) {
        this.pgEngine       = pgEngine;
        this.spatialBuilder = spatialBuilder;
        this.esClient       = esClient;
        this.assembler      = assembler;
        this.props          = props;
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

        // Build must clauses: always include geo_shape queries; add multi_match when text is present
        List<Query> mustQueries = new ArrayList<>(queriesOpt.get());
        boolean hasText = req.hasTextSearch();
        double applyMinScore = 0.0;

        if (hasText) {
            String text = req.textSearch();
            mustQueries.add(Query.of(q -> q.multiMatch(mm -> mm
                    .query(text)
                    .fields("full_text_blob", "item_name^2")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO"))));
            applyMinScore = props.getElasticsearch().getMinScore();
            log.info("es.spatial+text.request txId={} {}", req.transactionId(),
                    spatialBuilder.buildCombinedRequestJson(req.spatial(), text, alias, limit, applyMinScore));
        } else {
            log.info("es.spatial.request txId={} {}", req.transactionId(),
                    spatialBuilder.buildRequestJson(req.spatial(), alias, limit));
        }

        final List<Query> finalQueries = mustQueries;
        final double finalMinScore = applyMinScore;

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                var b = s.index(alias)
                        .size(limit)
                        .query(q -> q.bool(bq -> {
                            finalQueries.forEach(bq::must);
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
            if ("index_not_found_exception".equals(e.error().type())) {
                log.warn("es.engine.spatial.index-not-found alias={} transactionId={}", alias, req.transactionId());
                return List.of();
            }
            if ("search_phase_execution_exception".equals(e.error().type())
                    && e.error().rootCause().stream().anyMatch(rc ->
                            rc.reason() != null && rc.reason().contains("failed to find type for field"))) {
                String fieldHint = e.error().rootCause().get(0).reason();
                log.warn("es.engine.spatial.unknown-field hint='{}' transactionId={} — targets path not indexed; returning empty",
                        fieldHint, req.transactionId());
                return List.of();
            }
            throw e;
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

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }
}
