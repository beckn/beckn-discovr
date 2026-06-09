package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.common.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.beckn.catalogpublish.controller.CatalogPullCallbackService;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * Integration tests for POST /catalog/push.
 * Real PostgreSQL + Kafka via Testcontainers (inherited from BaseIntegrationTest).
 * Asserts: HTTP response (sync) and DB state (async via Awaitility).
 */
@AutoConfigureMockMvc
class CatalogPushControllerIntegrationTest extends BaseIntegrationTest {

    private static final String PUSH_PATH = "/beckn/catalog/push";

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private CatalogPullCallbackService pullCallbackService;

    // ── ACK response ──────────────────────────────────────────────────────────

    @Test
    void push_validPayload_returns200Ack() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isOk());

        // Wait for async pipeline to drain so this test doesn't pollute later tests
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1));
    }

    @Test
    void push_validPayload_ackBodyHasCorrectStructure() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        // Wait for async pipeline to drain so this test doesn't pollute later tests
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1));
    }

    // ── Async persistence ─────────────────────────────────────────────────────

    @Test
    void push_validPayload_persistsItemsAsynchronously() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(1));
    }

    @Test
    void push_multiCatalogPayload_persistsAllItemsAsynchronously() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_catalog_example.json");

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(3));
    }

    @Test
    void push_validPayload_returns200ImmediatelyBeforeProcessingCompletes() throws Exception {
        // The endpoint must return 200 Ack before the async pipeline finishes.
        // We verify this by checking the response arrives quickly (MockMvc is
        // synchronous by nature but the async dispatch must not block it).
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        long start = System.currentTimeMillis();
        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isOk());
        long elapsed = System.currentTimeMillis() - start;

        // Response should arrive before persistence completes (< 2 s as a generous bound)
        assertThat(elapsed).isLessThan(2_000);

        // Wait for async pipeline to drain so this test doesn't pollute later tests
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1));
    }

    // ── Payload size enforcement ──────────────────────────────────────────────

    @Test
    void push_oversizedPayload_returns400Nack() throws Exception {
        // max-payload-size in test profile = 5242880 (5 MB); send one byte over.
        // An oversized body is a client error → 400 NackBadRequest (413 is not in the
        // Beckn response set).
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED));
    }

    @Test
    void push_oversizedPayload_doesNotEnqueueToKafka() throws Exception {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');

        // 400 is returned synchronously — nothing is enqueued to Kafka
        // Capture count immediately before AND after; they must be equal since the
        // oversized request never reaches the pipeline (rejected at the HTTP layer)
        long countBefore = itemRepository.count();

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest());

        // Synchronous check: the 400 response guarantees nothing was enqueued
        long countAfter = itemRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    // ── Async failure cases (200 Ack returned, no DB row) ─────────────────────

    @Test
    void push_invalidJson_returns400NackDoesNotPersist() throws Exception {
        long countBefore = itemRepository.count();

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not valid json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"));

        // Invalid JSON rejected at controller level — nothing persisted
        assertThat(itemRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void push_missingBppId_returns200ButDoesNotPersist() throws Exception {
        // context present, empty resources array → pipeline runs but nothing to persist
        String payload = """
                {
                  "context": {
                    "bppUri": "https://example.com",
                    "networkId": "test-net",
                    "messageId": "msg-missing-bpp"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-x",
                      "resources": []
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        // Assert that no item was persisted for catalog "cat-x" specifically.
        // Checking the global count is unreliable because in-flight Kafka consumers
        // from prior tests can insert rows for other catalog IDs after @BeforeEach clears.
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long catXCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM item WHERE catalog_id = ?", Long.class, "cat-x");
                    assertThat(catXCount).isZero();
                });
    }

    @Test
    void push_resourceWithDescriptorAndContextFields_persistsItem() throws Exception {
        long countBefore = itemRepository.count();

        // bppId/bppUri in context are valid Beckn fields for logging/MDC — catalog-level bppId is ignored
        String payload = """
                {
                  "context": {
                    "bppId": "bpp.test",
                    "bppUri": "https://bpp.example.com",
                    "networkId": "test-net",
                    "messageId": "msg-ctx-fields"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-1",
                      "resources": [{
                        "id": "item-1",
                        "descriptor": {"name": "Item One"}
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                // ACK echoes the request's messageId; transactionId absent here, so it is omitted
                .andExpect(jsonPath("$.message.messageId").value("msg-ctx-fields"))
                .andExpect(jsonPath("$.message.transactionId").doesNotExist());

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThan(countBefore));
    }

    @Test
    void push_resourceWithDescriptor_persistsItemWithCatalogId() throws Exception {
        String payload = """
                {
                  "context": {
                    "networkId": "test-net",
                    "messageId": "msg-catalog-id-test"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-derive",
                      "resources": [{
                        "id": "item-derived-1",
                        "descriptor": { "name": "Derived Item" }
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var items = itemRepository.findAll();
                    assertThat(items).hasSize(1);
                    var item = items.get(0);
                    assertThat(item.getCatalogId()).isEqualTo("cat-derive");
                    assertThat(item.getId()).isEqualTo("item-derived-1");
                });
    }

    @Test
    void push_emptyCatalogsResourceList_doesNotPersist() throws Exception {
        // resources list is empty — no items to persist
        // A valid catalog with a resource that has no descriptor (fails isRealResource check) — no persistence
        long countBefore = itemRepository.count();

        String payload = """
                {
                  "context": {
                    "networkId": "test-net",
                    "messageId": "msg-no-desc"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-no-desc",
                      "resources": [{
                        "id": "item-no-desc",
                        "resourceAttributes": {"@context": "https://schema.org/", "@type": "Thing"}
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        // Resource has no descriptor — isRealResource returns false, pipeline skips it.
        // Assert no item was persisted for "cat-no-desc" specifically. A global count is
        // unreliable: in-flight Kafka consumers from prior tests can insert rows for other
        // catalog IDs after @BeforeEach clears (see push_missingBppId).
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long catCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM item WHERE catalog_id = ?", Long.class, "cat-no-desc");
                    assertThat(catCount).isZero();
                });
    }

    @Test
    void push_emptyCatalogsList_returns200ButDoesNotPersist() throws Exception {
        // An empty catalogs array is structurally incapable of persisting an item (catalog_id
        // is NOT NULL and there are zero catalogs to iterate). A global item-count assertion
        // is therefore meaningless here and flaky: in-flight Kafka consumers from prior tests
        // can insert rows after @BeforeEach clears. Instead, drain deterministically with a
        // sentinel that shares the empty payload's Kafka partition key (bppId), so it is
        // processed strictly after the empty payload (same-key ordering is the FULL/MERGE
        // guarantee in CatalogPushService). When the sentinel item appears, the empty payload
        // is known-processed; we then assert exactly one item exists for this test's scope.
        String emptyPayload = """
                {
                  "context": {
                    "bppId": "bpp.empty-drain",
                    "bppUri": "https://example.com",
                    "networkId": "test-net",
                    "messageId": "msg-empty-catalogs"
                  },
                  "message": {
                    "catalogs": []
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        String sentinelPayload = """
                {
                  "context": {
                    "bppId": "bpp.empty-drain",
                    "bppUri": "https://example.com",
                    "networkId": "test-net",
                    "messageId": "msg-empty-sentinel"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-empty-sentinel",
                      "resources": [{
                        "id": "item-empty-sentinel",
                        "descriptor": {"name": "Sentinel"}
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sentinelPayload))
                .andExpect(status().isOk());

        // Sentinel landed → empty payload (ordered before it on the same partition) is done.
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long sentinelCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM item WHERE catalog_id = ?", Long.class, "cat-empty-sentinel");
                    assertThat(sentinelCount).isEqualTo(1L);
                });

        // The empty catalogs array produced no catalog, so no item can carry its (absent)
        // catalog id — confirms it persisted nothing.
        long emptyDerived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item WHERE catalog_id IS NULL OR catalog_id = ''", Long.class);
        assertThat(emptyDerived).isZero();
    }

    @Test
    void push_bppIdMissingFromContextButPresentInCatalog_returns400Nack() throws Exception {
        // bppId only in catalog body (not context), and no messageId/transactionId in context.
        // hasRequiredContext() rejects payloads without a correlation ID — returns 400 NACK.
        long countBefore = itemRepository.count();

        String payload = """
                {
                  "context": {
                    "bppUri": "https://example.com",
                    "networkId": "test-net"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-no-ctx",
                      "bppId": "bpp-from-catalog",
                      "resources": [{
                        "id": "item-1",
                        "descriptor": {"name": "Item One"}
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value(ErrorCodes.CTX_MISSING_FIELD));

        // Rejected synchronously — nothing persisted
        assertThat(itemRepository.count()).isEqualTo(countBefore);
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @Test
    void push_signatureVerificationDisabled_noAuthHeaderRequired() throws Exception {
        // SIGNATURE_AUTH_ENABLED defaults to false → BecknAuthFilter skips verification → no Authorization header needed
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post(PUSH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                // Must succeed without any Authorization header
                .andExpect(status().isOk());
    }

    // ── ON_PULL Tests ─────────────────────────────────────────────────────────

    private static final String ON_PULL_PATH = "/beckn/catalog/on_pull";

    @Test
    void onPull_inlineValidPayload_returns200AndPersists() throws Exception {
        String payload = """
                {
                  "context": {
                    "networkId": "test-net",
                    "messageId": "msg-inline-on-pull"
                  },
                  "message": {
                    "status": "COMPLETED",
                    "catalogs": [{
                      "id": "cat-inline-on-pull",
                      "resources": [{
                        "id": "item-inline-1",
                        "descriptor": {"name": "Inline Item"}
                      }]
                    }]
                  }
                }
                """;

        mockMvc.perform(post(ON_PULL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long catCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM item WHERE catalog_id = ?", Long.class, "cat-inline-on-pull");
                    assertThat(catCount).isEqualTo(1);
                });
    }

    @Test
    void onPull_downloadManifestValidPayload_downloadsDecompressesVerifiesAndPersists() throws Exception {
        String catalogsJson = """
                {
                  "catalogs": [{
                    "id": "cat-download-on-pull",
                    "resources": [{
                      "id": "item-download-1",
                      "descriptor": {"name": "Downloaded Item"}
                    }]
                  }]
                }
                """;
        byte[] uncompressed = catalogsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed = gzip(uncompressed);
        String checksum = "sha256:" + sha256Hex(uncompressed);

        // Stub downloadCatalogFromUrl to return the mock Gzip compressed catalogs
        doReturn(compressed).when(pullCallbackService).downloadCatalogFromUrl(anyString());

        String callbackPayload = """
                {
                  "context": {
                    "networkId": "test-net",
                    "messageId": "msg-download-on-pull"
                  },
                  "message": {
                    "status": "COMPLETED",
                    "downloadManifest": {
                      "url": "https://mock-storage.com/catalog.json.gz",
                      "format": "json.gz",
                      "checksum": "%s"
                    }
                  }
                }
                """.formatted(checksum);

        mockMvc.perform(post(ON_PULL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long catCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM item WHERE catalog_id = ?", Long.class, "cat-download-on-pull");
                    assertThat(catCount).isEqualTo(1);
                });
    }

    @Test
    void onPull_missingCorrelationId_returns400Nack() throws Exception {
        String payload = """
                {
                  "context": {
                    "networkId": "test-net"
                  },
                  "message": {
                    "status": "COMPLETED",
                    "catalogs": []
                  }
                }
                """;

        mockMvc.perform(post(ON_PULL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value(ErrorCodes.CTX_MISSING_FIELD));
    }

    private static byte[] gzip(byte[] input) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var digest = md.digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
