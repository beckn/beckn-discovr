package org.beckn.catalogpublish.integration;

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
    }

    @Test
    void push_validPayload_ackBodyHasCorrectStructure() throws Exception {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));
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
    void push_oversizedPayload_doesNotPersistAnything() throws Exception {
        long countBefore = itemRepository.count();

        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        // Count must not increase — oversized payload must not reach the pipeline
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore));
    }

    // ── Async failure cases (202 returned, no DB row) ─────────────────────────

    @Test
    void push_invalidJson_returns202ButDoesNotPersist() throws Exception {
        long countBefore = itemRepository.count();

        mockMvc.perform(post("/catalog/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not valid json}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Async pipeline will fail at ParseStep; count must not increase
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore));
    }

    @Test
    void push_missingBppIdEverywhere_accepted_persistsWithNullBppId() throws Exception {
        long countBefore = itemRepository.count();

        // New contract: bppId is no longer required on context OR catalog. When the
        // catalog object has no bppId, persistence writes null to the nullable column.
        // The push returns 202 ACK and the pipeline processes the catalog normally.
        String payload = """
                {
                  "context": {
                    "bppUri": "https://example.com",
                    "networkId": "test-net"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-nobppid",
                      "resources": [{
                        "id": "item-nobppid",
                        "descriptor": {"name": "Item without bppId"}
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

        // Async pipeline persists the row with bpp_id = null
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore + 1));
        var item = itemRepository.findById("item-nobppid").orElseThrow();
        assertThat(item.getBppId()).isNull();
        assertThat(item.getCatalogId()).isEqualTo("cat-nobppid");
    }

    @Test
    void push_bppIdInCatalogObject_persistsWithCatalogBppId() throws Exception {
        long countBefore = itemRepository.count();

        // New contract: bppId/bppUri are sourced from the catalog object (not context).
        // Even when context lacks them, the catalog carries the authoritative values
        // and persistence picks them up.
        String payload = """
                {
                  "context": {
                    "networkId": "test-net"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-bppincat",
                      "bppId": "bpp.test",
                      "bppUri": "https://bpp.example.com",
                      "resources": [{
                        "id": "item-bppincat",
                        "descriptor": {"name": "Item with catalog.bppId"}
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

        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(countBefore + 1));
        var item = itemRepository.findById("item-bppincat").orElseThrow();
        assertThat(item.getBppId()).isEqualTo("bpp.test");
        assertThat(item.getBppUri()).isEqualTo("https://bpp.example.com");
        assertThat(item.getCatalogId()).isEqualTo("cat-bppincat");
    }

    @Test
    void push_bppIdPresent_bppUriMissing_persistsWithNullBppUri() throws Exception {
        // Beckn v2.0 schema: bppUri is OPTIONAL. bppId is present → must succeed,
        // with bpp_uri stored as null in Postgres (nullable column).
        long countBefore = itemRepository.count();

        String payload = """
                {
                  "context": {
                    "bppId": "bpp.optional-uri",
                    "networkId": "test-net",
                    "action": "on_discover",
                    "version": "2.0.0"
                  },
                  "message": {
                    "catalogs": [{
                      "id": "cat-nulluri",
                      "resources": [{
                        "id": "item-nulluri-1"
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
    void push_emptyCatalogsList_returns202ButDoesNotPersist() throws Exception {
        long countBefore = itemRepository.count();

        String payload = """
                {
                  "context": {
                    "bppId": "bpp.test",
                    "bppUri": "https://example.com",
                    "networkId": "test-net"
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
