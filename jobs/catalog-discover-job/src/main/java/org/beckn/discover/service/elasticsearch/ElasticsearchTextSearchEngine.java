package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.config.EsTextSearchCondition;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TextSearchEngine} backed by Elasticsearch.
 *
 * <p>Supports two modes controlled by {@code discovery.text-search.engine}:</p>
 * <ul>
 *   <li><b>native-els</b> — keyword BM25 {@code multi_match} across {@code full_text_blob}
 *       and {@code item_name} (boosted ×2). Fast, no AI dependency.</li>
 *   <li><b>els-semantic-search</b> — the raw query is enriched by {@link QueryEnricher} (LLM)
 *       with related terms and domain vocabulary, then embedded via {@link EmbeddingClient}
 *       and searched using ES {@code knn} on the {@code item_vector} field.</li>
 * </ul>
 *
 * <p>Switching between modes requires only a config change — no code changes.</p>
 */
@Service
@Conditional(EsTextSearchCondition.class)
public class ElasticsearchTextSearchEngine implements TextSearchEngine {

    private static final Logger log     = LoggerFactory.getLogger(ElasticsearchTextSearchEngine.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final ElasticsearchClient       esClient;
    private final EsSearchAssembler         assembler;
    private final ObjectMapper              objectMapper;
    private final String                    aliasName;
    private final int                       resultLimit;
    private final double                    minScore;
    private final int                       knnCandidates;
    private final Optional<EmbeddingClient> embeddingClient;
    private final Optional<QueryEnricher>   queryEnricher;

    public ElasticsearchTextSearchEngine(ElasticsearchClient esClient,
                                         EsSearchAssembler assembler,
                                         ObjectMapper objectMapper,
                                         DiscoveryProperties props,
                                         Optional<EmbeddingClient> embeddingClient,
                                         Optional<QueryEnricher> queryEnricher) {
        this.esClient        = esClient;
        this.assembler       = assembler;
        this.objectMapper    = objectMapper;
        this.embeddingClient = embeddingClient;
        this.queryEnricher   = queryEnricher;
        DiscoveryProperties.Elasticsearch es = props.getElasticsearch();
        this.aliasName     = es.getAliasName();
        this.resultLimit   = es.getResultLimit();
        this.minScore      = es.getMinScore();
        this.knnCandidates = Math.max(props.getTextSearch().getEmbeddingModel().getKnnCandidates(), this.resultLimit);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<Catalog> search(String text, QueryRequest context) throws Exception {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Text search query cannot be null or empty");

        String txId  = context.transactionId();
        Instant start = Instant.now();

        // ── Semantic search path (engine=els-semantic-search) ───────────────────────
        // embeddingClient and queryEnricher are present only when engine=els-semantic-search.
        // Any provider failure throws SemanticSearchException — no fallback to keyword search.
        if (embeddingClient.isPresent()) {
            // Step 1: enrich the raw query with synonyms and domain vocabulary
            String queryToEmbed = queryEnricher.isPresent()
                    ? queryEnricher.get().enrich(text)
                    : text;

            // Step 2: embed the enriched query
            // throws SemanticSearchException on provider failure; empty Optional → no results
            Optional<List<Float>> queryVector = embeddingClient.get().embed(queryToEmbed);
            if (queryVector.isEmpty()) {
                log.warn(LogEvent.ES_SEARCH_FAILED + ".empty-vector",
                        value("transactionId", txId));
                return List.of();
            }

            List<Float> vec = queryVector.get();
            log.debug(LogEvent.ES_SEARCH_STARTED + ".knn",
                    value("index", aliasName),
                    value("k", resultLimit),
                    value("numCandidates", knnCandidates),
                    value("minScore", minScore),
                    value("transactionId", txId));
            try {
                SearchResponse<Map> response = esClient.search(s -> s
                        .index(aliasName)
                        .minScore(minScore)
                        .size(resultLimit)
                        .knn(k -> k
                                .field("item_vector")
                                .queryVector(vec)
                                .k(resultLimit)
                                .numCandidates(knnCandidates)),
                        Map.class);
                return assembleAndLog(response, txId, start, "knn");
            } catch (ElasticsearchException e) {
                if ("index_not_found_exception".equals(e.error().type())) {
                    log.info(LogEvent.ES_SEARCH_COMPLETED + ".knn-index-not-found",
                            value("alias", aliasName),
                            value("transactionId", txId));
                    return List.of();
                }
                long ms = Duration.between(start, Instant.now()).toMillis();
                log.error(LogEvent.ES_SEARCH_FAILED + ".knn",
                        value("durationMs", ms),
                        value("transactionId", txId),
                        value("error", e.getMessage()),
                        e);
                throw new Exception("Elasticsearch knn search failed for transactionId=" + txId, e);
            }
        }

        // ── Keyword search path (engine=native-els only) ──────────────────────
        log.info(LogEvent.ES_SEARCH_STARTED + ".keyword",
                value("transactionId", txId),
                value("query", buildTextSearchJson(text)));
        try {
            SearchResponse<Map> response = esClient.search(s -> s
                    .index(aliasName)
                    .query(q -> q.multiMatch(mm -> mm
                            .query(text)
                            .fields("full_text_blob", "item_name^2", "item_rating_review_text")
                            .type(TextQueryType.BestFields)
                            .fuzziness("AUTO")))
                    .minScore(minScore)
                    .size(resultLimit),
                    Map.class);
            return assembleAndLog(response, txId, start, "keyword");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (ElasticsearchException e) {
            if ("index_not_found_exception".equals(e.error().type())) {
                log.info(LogEvent.ES_SEARCH_COMPLETED + ".index-not-found",
                        value("alias", aliasName),
                        value("transactionId", txId));
                return List.of();
            }
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.error(LogEvent.ES_SEARCH_FAILED,
                    value("durationMs", ms),
                    value("transactionId", txId),
                    value("error", e.getMessage()),
                    e);
            throw new Exception("Elasticsearch text search failed for transactionId=" + txId, e);
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.error(LogEvent.ES_SEARCH_FAILED,
                    value("durationMs", ms),
                    value("transactionId", txId),
                    value("error", e.getMessage()),
                    e);
            throw new Exception("Elasticsearch text search failed for transactionId=" + txId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Catalog> assembleAndLog(SearchResponse<Map> response, String txId, Instant start, String mode) {
        List<Map<String, Object>> hits = response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(m -> (Map<String, Object>) m)
                .toList();
        List<Catalog> catalogs = assembler.assemble(hits, txId);
        long ms = Duration.between(start, Instant.now()).toMillis();
        log.info(LogEvent.ES_SEARCH_COMPLETED,
                value("mode", mode),
                value("catalogs", catalogs.size()),
                value("hits", hits.size()),
                value("durationMs", ms),
                value("transactionId", txId));
        perfLog.info(LogEvent.ES_SEARCH_COMPLETED,
                value("mode", mode),
                value("durationMs", ms),
                value("catalogs", catalogs.size()),
                value("transactionId", txId));
        return catalogs;
    }

    private String buildTextSearchJson(String text) {
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", text);
        multiMatch.put("fields", List.of("full_text_blob", "item_name^2", "item_rating_review_text"));
        multiMatch.put("type", "best_fields");
        multiMatch.put("fuzziness", "AUTO");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_score", minScore);
        body.put("size", resultLimit);
        body.put("query", Map.of("multi_match", multiMatch));
        try {
            return "POST /" + aliasName + "/_search\n"
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            return "(json-serialization failed: " + e.getMessage() + ")";
        }
    }
}
