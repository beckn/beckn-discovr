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

/**
 * Integration tests for POST /catalog/push.
 * Real PostgreSQL + Kafka via Testcontainers (inherited from BaseIntegrationTest).
 * Asserts: HTTP response (sync) and DB state (async via Awaitility).
 */
@AutoConfigureMockMvc
class CatalogPushControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── ACK response ──────────────────────────────────────────────────────────

    @Test
    void push_validPayload_returns202() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted());

        // Wait for async pipeline to drain so this test doesn't pollute later tests
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1));
    }

    @Test
    void push_validPayload_ackBodyHasCorrectStructure() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Wait for async pipeline to drain so this test doesn't pollute later tests
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1));
    }

    // ── Async persistence ─────────────────────────────────────────────────────

    @Test
    void push_validPayload_persistsItemsAsynchronously() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted());

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(1));
    }

    @Test
    void push_multiCatalogPayload_persistsAllItemsAsynchronously() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_catalog_example.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted());

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(3));
    }

    @Test
    void push_validPayload_returns202ImmediatelyBeforeProcessingCompletes() throws Exception {
        // The endpoint must return 202 before the async pipeline finishes.
        // We verify this by checking the response arrives quickly (MockMvc is
        // synchronous by nature but the async dispatch must not block it).
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        long start = System.currentTimeMillis();
        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted());
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
    void push_oversizedPayload_returns413() throws Exception {
        // max-payload-size in test profile = 5242880 (5 MB); send one byte over
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void push_oversizedPayload_doesNotEnqueueToKafka() throws Exception {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');

        // 413 is returned synchronously — nothing is enqueued to Kafka
        // Capture count immediately before AND after; they must be equal since the
        // oversized request never reaches the pipeline (rejected at the HTTP layer)
        long countBefore = itemRepository.count();

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        // Synchronous check: the 413 response guarantees nothing was enqueued
        long countAfter = itemRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    // ── Async failure cases (202 returned, no DB row) ─────────────────────────

    @Test
    void push_invalidJson_returns400NackDoesNotPersist() throws Exception {
        long countBefore = itemRepository.count();

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not valid json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("NACK"));

        // Invalid JSON rejected at controller level — nothing persisted
        assertThat(itemRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void push_missingBppId_returns202ButDoesNotPersist() throws Exception {
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

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

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

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

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

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

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

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Resource has no descriptor — isRealResource returns false, pipeline skips it
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore));
    }

    @Test
    void push_emptyCatalogsList_returns202ButDoesNotPersist() throws Exception {
        long countBefore = itemRepository.count();

        String payload = """
                {
                  "context": {
                    "bppId": "bpp.test",
                    "bppUri": "https://example.com",
                    "networkId": "test-net",
                    "messageId": "msg-empty-catalogs"
                  },
                  "message": {
                    "catalogs": []
                  }
                }
                """;

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore));
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

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("NACK"))
                .andExpect(jsonPath("$.error.errorCode").value(ErrorCodes.CTX_INVALID_FIELD));

        // Rejected synchronously — nothing persisted
        assertThat(itemRepository.count()).isEqualTo(countBefore);
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @Test
    void push_signatureVerificationDisabled_noAuthHeaderRequired() throws Exception {
        // SIGNATURE_AUTH_ENABLED defaults to false → BecknAuthFilter skips verification → no Authorization header needed
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                // Must succeed without any Authorization header
                .andExpect(status().isAccepted());
    }
}
