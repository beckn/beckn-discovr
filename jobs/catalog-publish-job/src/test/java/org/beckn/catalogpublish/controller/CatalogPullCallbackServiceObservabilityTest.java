package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Receiver-level observability tests: per-callback/per-catalog metrics and the single-enqueue
 * contract. Drives the public async entrypoint directly (no Spring proxy → runs inline).
 */
class CatalogPullCallbackServiceObservabilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CatalogPullCallbackService newService(CatalogPushService push, CatalogPublishMetrics metrics) {
        return new CatalogPullCallbackService(push, mapper, metrics,
                new AppProperties(null, null, new AppProperties.Catalog(
                        10_000_000L, false,
                        "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                        1, 4, null, null, null, true, null, null, null, null)));
    }

    @Test
    void inline_recordsReceiverMetrics_acceptsValidCatalogs_rejectsMissingId_enqueuesOnce() {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = newService(push, metrics);

        // 2 catalogs: CAT-1 (3 resources, valid) + one with no id (rejected). pagination.total=10.
        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {
                    "status": "COMPLETED",
                    "pagination": {"total": 10},
                    "catalogs": [
                      {"id":"CAT-1","resources":[{"id":"r1"},{"id":"r2"},{"id":"r3"}]},
                      {"resources":[{"id":"x"}]}
                    ]
                  }
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        verify(metrics).recordOnPullReceived("inline");
        verify(metrics).recordOnPullCatalogsReturned("inline", 2);
        verify(metrics).recordOnPullPaginationTotal("inline", 10L);
        verify(metrics, times(1)).recordOnPullCatalogAccepted("inline");
        verify(metrics, times(1)).recordOnPullCatalogRejected("inline");
        verify(metrics, times(1)).recordOnPullCatalogProcessed("inline");
        verify(metrics).recordOnPullResourcesTotal("inline", 3); // only the accepted catalog's resources
        verify(metrics).recordOnPullProcessed("inline");

        // Single enqueue of the whole array (contract unchanged) — payload still carries both catalogs.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(1)).enqueueForProcessing(sent.capture());
        assertThat(sent.getValue()).contains("CAT-1").contains("\"catalogs\"");
    }

    @Test
    void inline_noPagination_doesNotRecordPaginationTotal() {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = newService(push, metrics);

        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"status":"COMPLETED","catalogs":[{"id":"CAT-1","resources":[]}]}
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        verify(metrics).recordOnPullCatalogsReturned("inline", 1);
        verify(metrics).recordOnPullResourcesTotal("inline", 0);
        // pagination absent → never recorded (no default-to-zero).
        verify(metrics, Mockito.never()).recordOnPullPaginationTotal(Mockito.anyString(), Mockito.anyLong());
    }
}
