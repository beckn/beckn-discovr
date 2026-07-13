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
    void inline_eachPerCatalogRecord_carriesOnlyItsMatchingPublishDirective() throws Exception {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = newService(push, metrics);

        // 2 catalogs, each with its OWN message-level directive (visibleTo differs per catalog).
        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1","subscriptionId":"sub-1"},
                  "message": {
                    "status": "COMPLETED",
                    "publishDirectives": [
                      {"catalogId":"cat-1","catalogType":"REGULAR","updateMode":"MERGE","visibleTo":["netA"]},
                      {"catalogId":"cat-2","catalogType":"REGULAR","updateMode":"MERGE","visibleTo":["netB"]}
                    ],
                    "catalogs": [
                      {"id":"cat-1","resources":[{"id":"r1"}]},
                      {"id":"cat-2","resources":[{"id":"r2"}]}
                    ]
                  }
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(2)).enqueueForProcessing(sent.capture());
        java.util.List<String> records = sent.getAllValues();

        String cat1Record = records.stream().filter(r -> r.contains("cat-1")).findFirst().orElseThrow();
        String cat2Record = records.stream().filter(r -> r.contains("cat-2")).findFirst().orElseThrow();

        // cat-1's record carries EXACTLY its directive (visibleTo=[netA]) and never netB, and the
        // directive is message-level (message.publishDirectives), not inside the catalog object.
        var cat1Msg = mapper.readTree(cat1Record).path("message");
        var cat1Directives = cat1Msg.path("publishDirectives");
        assertThat(cat1Directives.isArray()).isTrue();
        assertThat(cat1Directives).hasSize(1);
        assertThat(cat1Directives.get(0).path("catalogId").asText()).isEqualTo("cat-1");
        assertThat(cat1Directives.get(0).path("visibleTo").get(0).asText()).isEqualTo("netA");
        assertThat(cat1Record).doesNotContain("netB");
        // Directive lives at message level, not on the catalog object.
        assertThat(cat1Msg.path("catalogs").get(0).has("publishDirectives")).isFalse();

        var cat2Directives = mapper.readTree(cat2Record).path("message").path("publishDirectives");
        assertThat(cat2Directives).hasSize(1);
        assertThat(cat2Directives.get(0).path("catalogId").asText()).isEqualTo("cat-2");
        assertThat(cat2Directives.get(0).path("visibleTo").get(0).asText()).isEqualTo("netB");
        assertThat(cat2Record).doesNotContain("netA");
    }

    @Test
    void inline_catalogWithNoMatchingDirective_omitsPublishDirectives_noCrash() throws Exception {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = newService(push, metrics);

        // Directive only for cat-1; cat-2 has NO matching directive → its record omits publishDirectives
        // (PersistenceStep falls back to context.networkId), and processing does not crash.
        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {
                    "status": "COMPLETED",
                    "publishDirectives": [
                      {"catalogId":"cat-1","catalogType":"REGULAR","updateMode":"MERGE","visibleTo":["netA"]}
                    ],
                    "catalogs": [
                      {"id":"cat-1","resources":[{"id":"r1"}]},
                      {"id":"cat-2","resources":[{"id":"r2"}]}
                    ]
                  }
                }
                """;

        service.processPullCallbackAsynchronously(payload);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(2)).enqueueForProcessing(sent.capture());
        java.util.List<String> records = sent.getAllValues();

        String cat1Record = records.stream().filter(r -> r.contains("cat-1")).findFirst().orElseThrow();
        String cat2Record = records.stream().filter(r -> r.contains("cat-2")).findFirst().orElseThrow();

        assertThat(mapper.readTree(cat1Record).path("message").path("publishDirectives")).hasSize(1);
        // No matching directive for cat-2 → field omitted entirely (missing node), no crash.
        assertThat(mapper.readTree(cat2Record).path("message").has("publishDirectives")).isFalse();
    }

    @Test
    void download_perCatalogRecord_carriesDirectiveFromCallbackBody() throws Exception {
        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);

        // The downloaded GCS JSON contains ONLY { catalogs } (no publishDirectives, no pagination).
        // Stub the download seam to return that JSON verbatim.
        String downloadedJson = """
                {
                  "catalogs": [
                    {"id":"dl-1","resources":[{"id":"r1"}]}
                  ]
                }
                """;
        byte[] body = downloadedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));

        AppProperties props = new AppProperties(null, null, new AppProperties.Catalog(
                10_000_000L, false,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, null, null, null, true, null, null, null, null, null, null, null));
        CatalogPullCallbackService service = Mockito.spy(new CatalogPullCallbackService(
                push, mapper, metrics, props, new SecureCatalogDownloader(metrics, props)));
        Mockito.doReturn(body).when(service).downloadCatalogFromUrl(Mockito.anyString());

        // publishDirectives are read uniformly from the callback body message (alongside downloadManifest).
        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {
                    "status": "COMPLETED",
                    "publishDirectives": [
                      {"catalogId":"dl-1","catalogType":"REGULAR","updateMode":"MERGE","visibleTo":["netD"]}
                    ],
                    "downloadManifest": {
                      "url": "https://storage.googleapis.com/bucket/file.json",
                      "format": "json",
                      "checksum": "%s",
                      "expiresAt": "2099-12-31T23:59:59Z"
                    }
                  }
                }
                """.formatted(checksum);

        service.processPullCallbackAsynchronously(payload);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(push, times(1)).enqueueForProcessing(sent.capture());
        var directives = mapper.readTree(sent.getValue()).path("message").path("publishDirectives");
        assertThat(directives).hasSize(1);
        assertThat(directives.get(0).path("catalogId").asText()).isEqualTo("dl-1");
        assertThat(directives.get(0).path("visibleTo").get(0).asText()).isEqualTo("netD");
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
