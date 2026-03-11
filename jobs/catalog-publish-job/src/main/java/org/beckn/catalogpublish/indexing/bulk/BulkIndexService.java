package org.beckn.catalogpublish.indexing.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.indexing.EsIndexManager;
import org.beckn.catalogpublish.indexing.EsIndexerMetrics;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final int                 retryAttempts;
    private final long                retryInitialDelayMs;

    public BulkIndexService(ElasticsearchClient esClient,
                            EsIndexManager indexManager,
                            EsIndexerMetrics metrics,
                            AppProperties props) {
        this.esClient            = esClient;
        this.indexManager        = indexManager;
        this.metrics             = metrics;
        this.retryAttempts       = props.catalog().elasticsearch().retryAttempts();
        this.retryInitialDelayMs = props.catalog().elasticsearch().retryInitialDelayMs();
    }

    public BulkIndexResult index(String indexKey, List<Map<String, Object>> docs) {
        if (docs.isEmpty()) return new BulkIndexResult(List.of(), List.of());

        String indexName = indexManager.resolveIndexName(indexKey);
        Exception last   = null;
        long delay       = retryInitialDelayMs;

        try {
            indexManager.ensureIndex(indexName);
        } catch (Exception e) {
            log.error("es.bulk.ensure-index.failed index={} error={}", indexName, ErrorSanitizer.sanitize(e));
            return BulkIndexResult.allFailed(toFailedDocs(docs, "index ensure failed: " + e.getMessage()));
        }

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                return executeBulk(indexName, docs);
            } catch (ConnectException | SocketTimeoutException e) {
                last = e;
                metrics.incrementRetried();
                log.warn("es.bulk.retry attempt={}/{} index={} error={}", attempt, retryAttempts, indexName, e.getMessage());
                if (attempt < retryAttempts) sleep(delay);
                delay *= 2;
            } catch (Exception e) {
                // Non-transient (mapping conflict, auth) — don't retry
                log.error("es.bulk.non-retryable index={} error={}", indexName, ErrorSanitizer.sanitize(e));
                return BulkIndexResult.allFailed(toFailedDocs(docs, e.getMessage()));
            }
        }
        log.error("es.bulk.retries-exhausted index={}", indexName);
        metrics.incrementBatchFailure();
        return BulkIndexResult.allFailed(toFailedDocs(docs, last != null ? last.getMessage() : "retries exhausted"));
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private BulkIndexResult executeBulk(String indexName, List<Map<String, Object>> docs) throws Exception {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs) {
            String id = doc.get("bpp_id") + ":" + doc.get("item_id");
            bulk.operations(op -> op.index(i -> i.index(indexName).id(id).document(doc)));
        }

        BulkResponse response = esClient.bulk(bulk.build());
        List<String>                    succeeded = new ArrayList<>();
        List<BulkIndexResult.FailedDoc> failed    = new ArrayList<>();

        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                failed.add(new BulkIndexResult.FailedDoc(
                        extractItemId(item.id()), extractBppId(item.id()), item.error().reason()));
                metrics.incrementItemFailure();
                log.error("es.item.index.failed docId={} reason={}", item.id(), item.error().reason());
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
                        (String) d.get("item_id"), (String) d.get("bpp_id"), reason))
                .peek(d -> metrics.incrementItemFailure())
                .toList();
    }

    // docId format: "bppId:itemId"
    private String extractBppId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(0, i) : ""; }
    private String extractItemId(String docId) { int i = docId.indexOf(':'); return i > 0 ? docId.substring(i + 1) : docId; }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
