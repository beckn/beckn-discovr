package org.beckn.catalogpublish.indexing.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.indexing.EsIndexManager;
import org.beckn.catalogpublish.indexing.EsIndexerMetrics;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class BulkIndexService {

    private static final Logger log = LoggerFactory.getLogger(BulkIndexService.class);

    private final ElasticsearchClient esClient;
    private final EsIndexManager      indexManager;
    private final EsIndexerMetrics    metrics;

    public BulkIndexService(ElasticsearchClient esClient,
                            EsIndexManager indexManager,
                            EsIndexerMetrics metrics,
                            AppProperties props) {
        this.esClient     = esClient;
        this.indexManager = indexManager;
        this.metrics      = metrics;
    }

    /**
     * Deletes all ES documents matching the given catalogId from each of the specific
     * per-schema-type indices. Used by FULL replace mode.
     *
     * <p>Deletes are scoped to exact index names derived from {@code schemaTypes} via
     * {@link EsIndexManager#resolveIndexName(String)} — never a wildcard — to avoid
     * accidentally touching archive or replica indices that share the same prefix.
     *
     * @param catalogId   the catalog whose documents should be removed
     * @param schemaTypes distinct schema types for this catalog (derived from saved items)
     * @return total number of documents deleted across all targeted indices
     */
    public long deleteByCatalog(String catalogId, Set<String> schemaTypes) {
        if (schemaTypes.isEmpty()) {
            log.warn("event={} catalogId={} reason=no-schema-types-provided skipping ES delete",
                    LogEvent.FULL_REPLACE_ES_DELETED, catalogId);
            return 0L;
        }
        long totalDeleted = 0L;
        for (String schemaType : schemaTypes) {
            String indexName = indexManager.resolveIndexName(schemaType);
            try {
                var response = esClient.deleteByQuery(d -> d
                        .index(indexName)
                        .query(q -> q.term(t -> t.field("catalog_id").value(catalogId)))
                );
                long deleted = response.deleted() != null ? response.deleted() : 0;
                totalDeleted += deleted;
                log.info("event={} catalogId={} index={} deletedDocs={}",
                        LogEvent.FULL_REPLACE_ES_DELETED, catalogId, indexName, deleted);
            } catch (Exception e) {
                log.error("event={} catalogId={} index={} error={}",
                        LogEvent.ES_FAILED, catalogId, indexName, ErrorSanitizer.sanitize(e));
                throw new RuntimeException(
                        "ES deleteByQuery failed for FULL replace: catalogId=" + catalogId + " index=" + indexName, e);
            }
        }
        return totalDeleted;
    }

    @Retryable(
        retryFor = { ConnectException.class, SocketTimeoutException.class, EsRateLimitException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 30000)
    )
    public BulkIndexResult index(String indexKey, List<Map<String, Object>> docs) throws ConnectException, SocketTimeoutException, EsRateLimitException {
        if (docs.isEmpty()) return new BulkIndexResult(List.of(), List.of());

        String indexName = indexManager.resolveIndexName(indexKey);

        try {
            indexManager.ensureIndex(indexName);
        } catch (Exception e) {
            log.error("event={} reason=ensure-index-failed index={} error={}", LogEvent.ES_FAILED, indexName, ErrorSanitizer.sanitize(e));
            return BulkIndexResult.allFailed(toFailedDocs(docs, "index ensure failed: " + e.getMessage()));
        }

        try {
            return executeBulk(indexName, docs);
        } catch (ConnectException | SocketTimeoutException e) {
            metrics.incrementRetried();
            log.warn("event={} index={} error={}", LogEvent.ES_FAILED, indexName, e.getMessage());
            throw e; // let @Retryable handle retry
        } catch (ElasticsearchException e) {
            if (e.status() == 429) {
                metrics.incrementRetried();
                int attempt = currentRetryAttempt();
                log.warn("event={} reason=rate-limited index={} attempt={} error={}",
                        LogEvent.ES_FAILED, indexName, attempt, e.getMessage());
                throw new EsRateLimitException("ES returned HTTP 429 on index=" + indexName, e);
            }
            // Non-transient ES error (mapping conflict, auth, etc.) — don't retry
            log.error("event={} reason=non-retryable index={} error={}", LogEvent.ES_FAILED, indexName, ErrorSanitizer.sanitize(e));
            return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
        } catch (Exception e) {
            // Non-transient (mapping conflict, auth) — don't retry
            log.error("event={} reason=non-retryable index={} error={}", LogEvent.ES_FAILED, indexName, ErrorSanitizer.sanitize(e));
            return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
        }
    }

    @Recover
    public BulkIndexResult recoverIndex(ConnectException e, String indexKey, List<Map<String, Object>> docs) {
        log.error("event={} reason=retries-exhausted index={}", LogEvent.ES_FAILED, indexKey);
        metrics.incrementBatchFailure();
        return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
    }

    @Recover
    public BulkIndexResult recoverIndex(SocketTimeoutException e, String indexKey, List<Map<String, Object>> docs) {
        log.error("event={} reason=retries-exhausted index={}", LogEvent.ES_FAILED, indexKey);
        metrics.incrementBatchFailure();
        return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
    }

    @Recover
    public BulkIndexResult recoverIndex(EsRateLimitException e, String indexKey, List<Map<String, Object>> docs) {
        log.error("event={} reason=retries-exhausted-rate-limited index={}", LogEvent.ES_FAILED, indexKey);
        metrics.incrementBatchFailure();
        return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
    }

    /** Returns the current Spring Retry attempt count (1-based), or 0 if not inside a retry context. */
    private static int currentRetryAttempt() {
        RetryContext ctx = RetrySynchronizationManager.getContext();
        return ctx != null ? ctx.getRetryCount() + 1 : 0;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private BulkIndexResult executeBulk(String indexName, List<Map<String, Object>> docs) throws Exception {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs) {
            // ES doc ID: catalogId:resourceId — unique, content-addressable
            String id = doc.get("catalog_id") + ":" + doc.get("resource_id");
            bulk.operations(op -> op.index(i -> i.index(indexName).id(id).document(doc)));
        }

        BulkResponse response = esClient.bulk(bulk.build());
        List<String>                    succeeded = new ArrayList<>();
        List<BulkIndexResult.FailedDoc> failed    = new ArrayList<>();

        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                failed.add(new BulkIndexResult.FailedDoc(
                        extractResourceId(item.id()), extractCatalogId(item.id()), item.error().reason()));
                metrics.incrementResourceFailure();
                log.error("event={} docId={} reason={}", LogEvent.ES_FAILED, item.id(), item.error().reason());
            } else {
                succeeded.add(item.id());
                metrics.incrementIndexed();
            }
        }
        return new BulkIndexResult(succeeded, failed);
    }

    private List<BulkIndexResult.FailedDoc> toFailedDocs(List<Map<String, Object>> docs, String reason) {
        return docs.stream()
                .map(d -> new BulkIndexResult.FailedDoc(
                        (String) d.get("resource_id"), (String) d.get("catalog_id"), reason))
                .peek(d -> metrics.incrementResourceFailure())
                .toList();
    }

    // docId format: "catalogId:resourceId"
    private String extractCatalogId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(0, i) : ""; }
    private String extractResourceId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(i + 1) : docId; }
}
