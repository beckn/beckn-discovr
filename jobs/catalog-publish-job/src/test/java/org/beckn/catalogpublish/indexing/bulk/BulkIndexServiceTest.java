package org.beckn.catalogpublish.indexing.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.indexing.EsIndexManager;
import org.beckn.catalogpublish.indexing.EsIndexerMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkIndexServiceTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private EsIndexManager indexManager;
    @Mock
    private EsIndexerMetrics metrics;
    @Mock
    private AppProperties props;
    @Mock
    private AppProperties.Catalog catalogProps;
    @Mock
    private AppProperties.Elasticsearch esProps;

    private BulkIndexService bulkIndexService;

    @BeforeEach
    void setUp() {
        bulkIndexService = new BulkIndexService(esClient, indexManager, metrics, props);
    }

    @Test
    void index_constructsCorrectDocumentId() throws Exception {
        // GIVEN: Two documents with same resource_id but different catalog_id
        // ES doc ID format is catalogId:resourceId
        Map<String, Object> doc1 = Map.of("catalog_id", "cat-1", "resource_id", "res-1", "name", "Item 1");
        Map<String, Object> doc2 = Map.of("catalog_id", "cat-2", "resource_id", "res-1", "name", "Item 2");
        List<Map<String, Object>> docs = List.of(doc1, doc2);

        when(indexManager.resolveIndexName("test")).thenReturn("test-index");

        // Mock successful bulk response
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item1 = mock(BulkResponseItem.class);
        BulkResponseItem item2 = mock(BulkResponseItem.class);
        when(item1.id()).thenReturn("cat-1:res-1");
        when(item2.id()).thenReturn("cat-2:res-1");
        when(response.items()).thenReturn(List.of(item1, item2));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // WHEN
        bulkIndexService.index("test", docs);

        // THEN: Verify the BulkRequest was sent and IDs are in catalogId:resourceId format
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(captor.capture());

        assertThat(item1.id()).isEqualTo("cat-1:res-1");
        assertThat(item2.id()).isEqualTo("cat-2:res-1");
    }

    @Test
    void index_handlesMissingResourceIdGracefully() throws Exception {
        // GIVEN: A document missing resource_id
        Map<String, Object> doc = Map.of("catalog_id", "cat-1", "name", "Missing ID");

        when(indexManager.resolveIndexName("test")).thenReturn("test-index");

        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item = mock(BulkResponseItem.class);
        when(item.id()).thenReturn("cat-1:null");
        when(response.items()).thenReturn(List.of(item));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // WHEN
        bulkIndexService.index("test", List.of(doc));

        // THEN: Verify catalog_id:null is used (matches implementation behavior for missing resource_id)
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(captor.capture());
        assertThat(item.id()).isEqualTo("cat-1:null");
    }

    // ── C4: 429 rate-limit retry tests ────────────────────────────────────────

    @Test
    void index_elasticsearch429_throwsEsRateLimitException() throws Exception {
        // GIVEN: ES returns 429 — should be re-thrown as EsRateLimitException (retryable)
        Map<String, Object> doc = Map.of("catalog_id", "cat-1", "resource_id", "res-1");
        when(indexManager.resolveIndexName("test")).thenReturn("test-index");

        ElasticsearchException e429 = buildElasticsearchException(429);
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(e429);

        // WHEN / THEN: EsRateLimitException surfaces so @Retryable can intercept it
        assertThatThrownBy(() -> bulkIndexService.index("test", List.of(doc)))
                .isInstanceOf(EsRateLimitException.class)
                .hasMessageContaining("429");

        verify(metrics).incrementRetried();
    }

    @Test
    void index_elasticsearchNon429_returnsAllFailed_noRetry() throws Exception {
        // GIVEN: ES returns 403 (auth) — must NOT be wrapped as EsRateLimitException
        Map<String, Object> doc = Map.of("catalog_id", "cat-1", "resource_id", "res-1");
        when(indexManager.resolveIndexName("test")).thenReturn("test-index");

        ElasticsearchException e403 = buildElasticsearchException(403);
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(e403);

        // WHEN
        BulkIndexResult result = bulkIndexService.index("test", List.of(doc));

        // THEN: marked as failed, no retry counter incremented
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.succeeded()).isEmpty();
        verify(metrics, never()).incrementRetried();
    }

    @Test
    void index_connectException_incrementsRetryMetricAndRethrows() throws Exception {
        // GIVEN: connection refused (transient) — must rethrow for @Retryable
        Map<String, Object> doc = Map.of("catalog_id", "cat-1", "resource_id", "res-1");
        when(indexManager.resolveIndexName("test")).thenReturn("test-index");
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(new ConnectException("Connection refused"));

        assertThatThrownBy(() -> bulkIndexService.index("test", List.of(doc)))
                .isInstanceOf(ConnectException.class);

        verify(metrics).incrementRetried();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a mock {@link ElasticsearchException} that reports a specific HTTP status.
     *
     * <p>The ES client library does not expose a public constructor for setting
     * arbitrary status codes on {@link ElasticsearchException}, but the class has
     * a constructor that takes an {@link ErrorResponse} object. We create a minimal
     * version using Mockito rather than fighting the sealed hierarchy.</p>
     */
    private static ElasticsearchException buildElasticsearchException(int statusCode) {
        var errorCause = ErrorCause.of(ec -> ec.type("status_exception").reason("HTTP " + statusCode));
        var errorResponse = ErrorResponse.of(er -> er.status(statusCode).error(errorCause));
        return new ElasticsearchException("test-url", errorResponse);
    }
}
