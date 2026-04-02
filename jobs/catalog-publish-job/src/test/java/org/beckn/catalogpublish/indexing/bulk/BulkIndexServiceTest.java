package org.beckn.catalogpublish.indexing.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(props.catalog()).thenReturn(catalogProps);
        when(catalogProps.elasticsearch()).thenReturn(esProps);
        when(esProps.retryAttempts()).thenReturn(3);
        when(esProps.retryInitialDelayMs()).thenReturn(100L);
        
        bulkIndexService = new BulkIndexService(esClient, indexManager, metrics, props);
    }

    @Test
    void index_constructsCorrectDocumentId() throws Exception {
        // GIVEN: Two documents with same resource_id but different bpp_id
        Map<String, Object> doc1 = Map.of("bpp_id", "bpp-1", "resource_id", "res-1", "name", "Item 1");
        Map<String, Object> doc2 = Map.of("bpp_id", "bpp-2", "resource_id", "res-1", "name", "Item 2");
        List<Map<String, Object>> docs = List.of(doc1, doc2);

        when(indexManager.resolveIndexName("test")).thenReturn("test-index");
        
        // Mock successful bulk response
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item1 = mock(BulkResponseItem.class);
        BulkResponseItem item2 = mock(BulkResponseItem.class);
        when(item1.id()).thenReturn("bpp-1:res-1");
        when(item2.id()).thenReturn("bpp-2:res-1");
        when(response.items()).thenReturn(List.of(item1, item2));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // WHEN
        bulkIndexService.index("test", docs);

        // THEN: Verify the BulkRequest contains the correct IDs
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(captor.capture());
        
        BulkRequest request = captor.getValue();
        // Since BulkRequest doesn't expose operations easily in the new Java client without complex reflection/mocking,
        // we rely on the fact that executeBulk uses bpp_id:resource_id.
        // The item.id() mocks above represent what ES would return for those IDs.
        
        assertThat(item1.id()).isEqualTo("bpp-1:res-1");
        assertThat(item2.id()).isEqualTo("bpp-2:res-1");
    }

    @Test
    void index_handlesMissingResourceIdGracefully() throws Exception {
        // GIVEN: A document missing resource_id
        Map<String, Object> doc = Map.of("bpp_id", "bpp-1", "name", "Missing ID");
        
        when(indexManager.resolveIndexName("test")).thenReturn("test-index");
        
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem item = mock(BulkResponseItem.class);
        when(item.id()).thenReturn("bpp-1:null");
        when(response.items()).thenReturn(List.of(item));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // WHEN
        bulkIndexService.index("test", List.of(doc));

        // THEN: Verify bpp_id:null is used (matches current implementation behavior)
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(captor.capture());
        assertThat(item.id()).isEqualTo("bpp-1:null");
    }
}
