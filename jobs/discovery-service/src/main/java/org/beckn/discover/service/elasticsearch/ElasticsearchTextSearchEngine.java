package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link TextSearchEngine} backed by Elasticsearch (Path D).
 *
 * <p>Activate by setting:</p>
 * <pre>
 * discovery:
 *   text-search:
 *     engine: elasticsearch
 * </pre>
 *
 * <p>Executes a {@code multi_match} query with {@code AUTO} fuzziness across
 * {@code full_text_blob} (synthetic field built at index time) and
 * {@code item_name} (boosted ×2). Hits are assembled into {@link Catalog}
 * objects grouped by {@code catalog_id} via {@link EsSearchAssembler}.</p>
 *
 * <p>Switching back to NLWeb requires only changing the YAML property —
 * no code changes.</p>
 */
@Service
@ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "elasticsearch")
public class ElasticsearchTextSearchEngine implements TextSearchEngine {

    private static final Logger log     = LoggerFactory.getLogger(ElasticsearchTextSearchEngine.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final ElasticsearchClient esClient;
    private final EsSearchAssembler   assembler;
    private final String              aliasName;
    private final int                 resultLimit;
    private final double              minScore;

    public ElasticsearchTextSearchEngine(ElasticsearchClient esClient,
                                         EsSearchAssembler assembler,
                                         DiscoveryProperties props) {
        this.esClient    = esClient;
        this.assembler   = assembler;
        DiscoveryProperties.Elasticsearch es = props.getElasticsearch();
        this.aliasName   = es.getAliasName();
        this.resultLimit = es.getResultLimit();
        this.minScore    = es.getMinScore();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<Catalog> search(String text, QueryRequest context) throws Exception {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Text search query cannot be null or empty");

        String txId = context.transactionId();
        log.info("es.search.start transactionId={} query='{}'", txId, text);
        Instant start = Instant.now();

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                    .index(aliasName)
                    .query(q -> q.multiMatch(mm -> mm
                            .query(text)
                            .fields("full_text_blob", "item_name^2")
                            .type(TextQueryType.BestFields)
                            .fuzziness("AUTO")))
                    .minScore(minScore)
                    .size(resultLimit),
                    Map.class);

            List<Map<String, Object>> hits = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(m -> (Map<String, Object>) m)
                    .toList();

            List<Catalog> catalogs = assembler.assemble(hits, txId);

            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("es.search.success catalogs={} hits={} durationMs={} transactionId={}",
                    catalogs.size(), hits.size(), ms, txId);
            perfLog.info("es.search durationMs={} catalogs={} transactionId={}", ms, catalogs.size(), txId);

            return catalogs;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.error("es.search.failed durationMs={} transactionId={} error={}", ms, txId, e.getMessage(), e);
            throw new Exception("Elasticsearch text search failed for transactionId=" + txId, e);
        }
    }
}
