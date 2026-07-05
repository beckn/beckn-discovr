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
 * Receiver-level observability tests: per-callback/per-catalog metrics and the per-catalog enqueue
 * contract (PR #393 C2 — each catalog is its own Kafka record, never one giant array record). Drives
 * the public async entrypoint directly (no Spring proxy → runs inline).
 */
class CatalogPullCallbackServiceObservabilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CatalogPullCallbackService newService(CatalogPushService push, CatalogPublishMetrics metrics) {
        return newServiceWithCap(push, metrics, 10_000_000L);
    }

    private CatalogPullCallbackService newServiceWithCap(
            CatalogPushService push, CatalogPublishMetrics metrics, long maxPayloadSize) {
        AppProperties props = new AppProperties(null, null, new AppProperties.Catalog(
                maxPayloadSize, false,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, null, null, null, true, null, null, null, null, null, null, null));
        return new CatalogPullCallbackService(push, mapper, metrics, props,
                new SecureCatalogDownloader(metrics, props));
    }

    @Test
    void oversizeCatalog_rejectedBeforeEnqueue_noRuntimeThrow() {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        // Tiny cap (200 bytes) so the single catalog's serialized record exceeds it → clean rejection,
        // never a RecordTooLargeException at enqueue.
        CatalogPullCallbackService service = newServiceWithCap(push, metrics, 200L);

        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"status":"COMPLETED","catalogs":[
                    {"id":"BIG","resources":[{"id":"r1","descriptor":{"name":"a-reasonably-long-descriptor-name-to-exceed-the-tiny-cap"}}]}
                  ]}
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        verify(metrics, times(1)).recordOnPullCatalogRejected("inline");
        verify(metrics, Mockito.never()).recordOnPullCatalogAccepted(Mockito.anyString());
        verify(metrics, Mockito.never()).recordOnPullCatalogProcessed(Mockito.anyString());
        Mockito.verify(push, Mockito.never()).enqueueForProcessing(Mockito.anyString());
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

        // C2: per-catalog enqueue. Only the ONE valid catalog (CAT-1) is enqueued (the missing-id
        // catalog is rejected before enqueue), so exactly one record is produced and it carries CAT-1.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(1)).enqueueForProcessing(sent.capture());
        assertThat(sent.getValue()).contains("CAT-1").contains("\"catalogs\"");
    }

    @Test
    void multipleCatalogs_enqueuedPerCatalog_eachRecordCarriesExactlyOneCatalog() {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = newService(push, metrics);

        // 3 valid catalogs → 3 separate Kafka records (never one giant array record).
        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1","action":"catalog/on_pull"},
                  "message": {
                    "status": "COMPLETED",
                    "catalogs": [
                      {"id":"CAT-1","resources":[{"id":"r1"}]},
                      {"id":"CAT-2","resources":[{"id":"r2"},{"id":"r3"}]},
                      {"id":"CAT-3","resources":[]}
                    ]
                  }
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        verify(metrics).recordOnPullCatalogsReturned("inline", 3);
        verify(metrics, times(3)).recordOnPullCatalogAccepted("inline");
        verify(metrics, times(3)).recordOnPullCatalogProcessed("inline");
        verify(metrics, Mockito.never()).recordOnPullCatalogRejected(Mockito.anyString());

        // C2: one enqueue PER catalog, each record carrying exactly one catalog id and the original context.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(3)).enqueueForProcessing(sent.capture());
        java.util.List<String> records = sent.getAllValues();
        assertThat(records).hasSize(3);
        assertThat(records).anySatisfy(r -> assertThat(r).contains("CAT-1"));
        assertThat(records).anySatisfy(r -> assertThat(r).contains("CAT-2"));
        assertThat(records).anySatisfy(r -> assertThat(r).contains("CAT-3"));
        // Each record is one catalog only: a record mentioning CAT-1 must not also carry CAT-2/CAT-3.
        String cat1Record = records.stream().filter(r -> r.contains("CAT-1")).findFirst().orElseThrow();
        assertThat(cat1Record).doesNotContain("CAT-2").doesNotContain("CAT-3");
        // Original context (action preserved) carried on each record.
        assertThat(cat1Record).contains("catalog/on_pull");
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
