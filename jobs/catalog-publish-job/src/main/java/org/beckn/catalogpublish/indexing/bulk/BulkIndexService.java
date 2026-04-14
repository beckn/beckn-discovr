package org.beckn.catalogpublish.indexing.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class BulkIndexService {

    private static final Logger log = LoggerFactory.getLogger(BulkIndexService.class);

    private final ElasticsearchClient esClient;
    private final EsIndexManager      indexManager;
    private final EsIndexerMetrics    metrics;
    private final String              indexPatternForDelete;

    public BulkIndexService(ElasticsearchClient esClient,
                            EsIndexManager indexManager,
                            EsIndexerMetrics metrics,
                            AppProperties props) {
        this.esClient              = esClient;
        this.indexManager          = indexManager;
        this.metrics               = metrics;
        this.indexPatternForDelete = props.catalog().elasticsearch().indexPrefix() + "-*";
    }

    /**
     * Deletes all ES documents matching the given catalogId across all index patterns.
     * Used by FULL replace mode.
     *
     * @return number of documents deleted
     */
    public long deleteByCatalog(String catalogId) {
        try {
            var response = esClient.deleteByQuery(d -> d
                    .index(indexPatternForDelete)
                    .query(q -> q.term(t -> t.field("catalog_id").value(catalogId)))
            );
            long deleted = response.deleted() != null ? response.deleted() : 0;
            log.info("event={} catalogId={} deletedDocs={}",
                    LogEvent.FULL_REPLACE_ES_DELETED, catalogId, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("event={} catalogId={} error={}",
                    LogEvent.ES_FAILED, catalogId, ErrorSanitizer.sanitize(e));
            throw new RuntimeException("ES deleteByQuery failed for FULL replace: " + catalogId, e);
        }
    }

    @Retryable(
        retryFor = { ConnectException.class, SocketTimeoutException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 30000)
    )
    public BulkIndexResult index(String indexKey, List<Map<String, Object>> docs) throws ConnectException, SocketTimeoutException {
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
                metrics.incrementItemFailure();
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
                .peek(d -> metrics.incrementItemFailure())
                .toList();
    }

    // docId format: "catalogId:resourceId"
    private String extractCatalogId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(0, i) : ""; }
    private String extractResourceId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(i + 1) : docId; }
}
