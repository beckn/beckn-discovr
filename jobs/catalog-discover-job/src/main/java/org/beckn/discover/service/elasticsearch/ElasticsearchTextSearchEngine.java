package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
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
import org.beckn.discover.util.ErrorSanitizer;
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
import java.util.function.Supplier;

/**
 * {@link TextSearchEngine} backed by Elasticsearch.
 *
 * <p>Supports two modes controlled by {@code discovery.text-search.engine}:</p>
 * <ul>
 *   <li><b>native-els</b> — keyword BM25 {@code multi_match} across {@code full_text_blob}
 *       and {@code resource_name} (boosted ×2). Fast, no AI dependency.</li>
 *   <li><b>els-semantic-search</b> — the raw query is enriched by {@link QueryEnricher} (LLM)
 *       with related terms and domain vocabulary, then embedded via {@link EmbeddingClient}
 *       and searched using ES {@code knn} on the {@code resource_vector} field.</li>
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
    private final List<String>              multiMatchFields;
    private final double                    relativeScoreThreshold;
    private final double                    tieBreaker;

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
        DiscoveryProperties.Elasticsearch esConfig = props.getElasticsearch();
        this.aliasName              = esConfig.getAliasName();
        this.resultLimit            = esConfig.getResultLimit();
        this.minScore               = esConfig.getMinScore();
        this.knnCandidates          = Math.max(props.getTextSearch().getEmbeddingModel().getKnnCandidates(), this.resultLimit);
        this.relativeScoreThreshold = esConfig.getRelativeScoreThreshold();
        this.tieBreaker             = esConfig.getTieBreaker();
        List<String> fields = esConfig.getMultiMatchFields();
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException(
                    "discovery.elasticsearch.multiMatchFields must not be empty");
        }
        this.multiMatchFields = List.copyOf(fields);
    }

    /**
     * Returns {@code true}: this engine injects schema context filters directly
     * into ES queries (both BM25 and KNN paths), so
     * {@link org.beckn.discover.service.response.CatalogPipeline} step 1 can
     * be safely skipped for this engine.
     */
    @Override
    public boolean appliesSchemaFilter() {
        return true;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<Catalog> search(String text, QueryRequest queryRequest) throws Exception {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Text search query cannot be null or empty");

        String txId  = queryRequest.transactionId();
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
            List<Query> schemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(queryRequest);
            log.debug(LogEvent.ES_SEARCH_STARTED + ".knn",
                    value("index", aliasName),
                    value("k", resultLimit),
                    value("numCandidates", knnCandidates),
                    value("minScore", minScore),
                    value("schemaFilters", schemaFilters.size()),
                    value("transactionId", txId));
            try {
                SearchResponse<Map> response = esClient.search(s -> s
                        .index(aliasName)
                        .minScore(minScore)
                        .size(resultLimit)
                        // H1: exclude large fields not used by EsSearchAssembler
                        .source(sf -> sf.filter(f -> f.excludes("full_text_blob", "resource_vector", "indexed_at")))
                        .trackTotalHits(t -> t.enabled(false))
                        .knn(k -> {
                            var kb = k.field("resource_vector")
                                    .queryVector(vec)
                                    .k(resultLimit)
                                    .numCandidates(knnCandidates);
                            schemaFilters.forEach(kb::filter);
                            EsNetworkFilterBuilder.build(queryRequest).ifPresent(kb::filter);
                            EsActiveValidityFilterBuilder.build(queryRequest, queryRequest.now()).ifPresent(kb::filter);
                            return kb;
                        }),
                        Map.class);
                // knn cosine scores are already normalized to a tight range by minScore;
                // relative filtering is unnecessary and could over-prune.
                return assembleAndLog(response.hits().hits(), txId, start, "knn");
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
        List<Query> keywordSchemaFilters = EsSchemaFilterBuilder.buildSchemaFilters(queryRequest);
        // H7: Move serialization to DEBUG with a lazy Supplier — avoids Jackson cost on every request
        log.debug(LogEvent.ES_SEARCH_STARTED + ".keyword",
                value("transactionId", txId),
                value("schemaFilters", keywordSchemaFilters.size()),
                value("query", (Supplier<String>) () -> buildTextSearchJson(ErrorSanitizer.sanitize(text))));
        List<String> blobFields = multiMatchFields.stream()
                .filter(f -> f.startsWith("full_text_blob"))
                .toList();
        List<String> scoringFields = multiMatchFields.stream()
                .filter(f -> !f.startsWith("full_text_blob"))
                .toList();
        try {
            SearchResponse<Map> response = esClient.search(s -> s
                    .index(aliasName)
                    // H1: exclude large fields not used by EsSearchAssembler
                    .source(sf -> sf.filter(f -> f.excludes("full_text_blob", "resource_vector", "indexed_at")))
                    .query(q -> q.bool(b -> {
                        // full_text_blob: gating clause — doc must contain query terms somewhere.
                        // Scored by BM25 via beckn_text analyzer (stemming + stop-words); no fuzziness
                        // needed since the blob already captures all catalog text.
                        if (!blobFields.isEmpty()) {
                            b.must(Query.of(mq -> mq.match(m -> m
                                    .field(blobFields.get(0))
                                    .query(text)
                                    .operator(Operator.And))));
                        }
                        // Scoring fields: boost documents where the query matches the resource/catalog/
                        // provider name directly. should (not must) so blob-only matches still qualify.
                        // No fuzziness — beckn_synonyms + english_stemmer handle recall.
                        if (!scoringFields.isEmpty()) {
                            b.should(Query.of(mq -> mq.multiMatch(mm -> mm
                                    .query(text)
                                    .fields(scoringFields)
                                    .type(TextQueryType.BestFields)
                                    .tieBreaker(tieBreaker))));
                        }
                        keywordSchemaFilters.forEach(b::filter);
                        EsNetworkFilterBuilder.build(queryRequest).ifPresent(b::filter);
                        EsActiveValidityFilterBuilder.build(queryRequest, queryRequest.now()).ifPresent(b::filter);
                        return b;
                    }))
                    .minScore(minScore)
                    .size(resultLimit)
                    .trackTotalHits(t -> t.enabled(false)),
                    Map.class);
            var filteredHits = filterByRelativeScore(response.hits().hits(), txId);
            return assembleAndLog(filteredHits, txId, start, "keyword");
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Catalog> assembleAndLog(List<Hit<Map>> rawHits, String txId, Instant start, String mode) {
        List<Map<String, Object>> hits = rawHits.stream()
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

    @SuppressWarnings("rawtypes")
    private List<Hit<Map>> filterByRelativeScore(List<Hit<Map>> hits, String txId) {
        if (relativeScoreThreshold <= 0.0 || hits.isEmpty()) {
            return hits;
        }
        Double topScore = hits.get(0).score();
        if (topScore == null || topScore <= 0.0) {
            return hits;
        }
        double cutoff = topScore * relativeScoreThreshold;
        var filtered = hits.stream()
                .filter(h -> h.score() != null && h.score() >= cutoff)
                .toList();
        int dropped = hits.size() - filtered.size();
        if (dropped > 0) {
            log.debug(LogEvent.ES_SEARCH_COMPLETED + ".relative-score-filter",
                    value("dropped", dropped),
                    value("kept", filtered.size()),
                    value("topScore", topScore),
                    value("cutoff", cutoff),
                    value("threshold", relativeScoreThreshold),
                    value("transactionId", txId));
        }
        return filtered;
    }

    private String buildTextSearchJson(String text) {
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", text);
        multiMatch.put("fields", multiMatchFields);
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
