package org.beckn.discover.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.*;

@AutoConfigureMockMvc
class DiscoveryControllerIntegrationTest extends BaseIntegrationTest {

        private static final Path REQUEST_FIXTURES = Path.of("src", "test", "resources", "fixtures", "requests");

        @Autowired
        private MockMvc mockMvc;

        @Test
        void postDiscoverReturnsCatalogsFromService() throws Exception {
                // POST is now async: validates auth+schema, publishes to Kafka, returns ACK.
                // The actual catalog response is delivered asynchronously via response-dispatcher.
                String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void getDiscoverReturnsCatalogsFromService() throws Exception {
                String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                ResultActions result = mockMvc.perform(get("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                // Validate context fields
                                .andExpect(jsonPath("$.context").exists())
                                // Validate catalogs
                                .andExpect(jsonPath("$.message.catalogs", hasSize(1)))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:id']").exists())
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:descriptor']['schema:name']")
                                                .value("EV Charging Services Network"))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:descriptor']['schema:image']",
                                                hasSize(2)))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:descriptor']['schema:image'][0]")
                                                .value("https://example.com/images/ev-charging-network.jpg"))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:descriptor']['schema:image'][1]")
                                                .value("https://example.com/images/charging-station-banner.png"))
                                // Validate items
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:items']", hasSize(1)))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:items'][0]['beckn:id']")
                                                .value("ev-charger-ccs2-001"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:descriptor']['schema:name']")
                                                .value("DC Fast Charger - CCS2 (60kW)"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:descriptor']['schema:image']",
                                                hasSize(2)))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:descriptor']['schema:image'][0]")
                                                .value("https://example.com/images/ev-charger-ccs2-60kw.jpg"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:descriptor']['schema:image'][1]")
                                                .value("https://example.com/images/charging-station-ccs2.png"))
                                // Validate provider
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:provider']['beckn:id']")
                                                .value("ecopower-charging"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0]['beckn:items'][0]['beckn:itemAttributes'].connectorType")
                                                .value("CCS2"))
                                // Validate offers
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:offers']", hasSize(greaterThanOrEqualTo(1))))
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:offers'][0]['beckn:id']").exists())
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:offers'][0]['beckn:items']").isArray())
                                .andExpect(jsonPath("$.message.catalogs[0]['beckn:offers'][0]['beckn:items'][0]")
                                                .value("ev-charger-ccs2-001"));
        }

        @Test
        void postDiscoverWithInvalidSchemaReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_missing_message_spatial.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.error.message", containsString("Schema validation failed")));
        }

        @Test
        void getDiscoverWithInvalidSchemaReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_missing_context.json");

                ResultActions result = mockMvc.perform(get("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.error.message", containsString("Schema validation failed")))
                                .andExpect(jsonPath("$.transaction_id").exists());
        }

        @Test
        void postDiscoverWithMissingTransactionIdReturnsSuccess() throws Exception {
                // Note: transaction_id is optional in the schema, so this passes validation.
                // POST is async — returns ACK (transaction_id will be null/absent).
                String payload = readFixture("invalid_missing_transaction_id.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithInvalidUuidReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_invalid_uuid.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.error.message", anyOf(
                                                containsString("transaction_id"),
                                                containsString("message_id"),
                                                containsString("invalid uuid"))))
                                .andExpect(jsonPath("$.transaction_id").exists());
        }

        @Test
        void postDiscoverWithEmptyBodyReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_empty_body.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.transaction_id").exists());
        }

        @Test
        void postDiscoverWithSpatialQueryReturnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_spatial_query.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithCombinedFiltersReturnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_combined_jsonpath_spatial.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithEmptyFiltersReturnsBadRequest() throws Exception {
                // Empty message {} fails schema validation - requires at least one of
                // text_search, filters, or spatial
                String payload = readFixture("empty_filters.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.error.message", containsString("Schema validation failed")));
        }

        @Test
        void postDiscoverWithOfferFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_offer_by_id.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithRelativeFilterExpression_returnsBadRequest() throws Exception {
                String payload = readFixture("invalid_relative_filter_expression.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.ack_status").value("NACK"))
                                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                                .andExpect(jsonPath("$.error.paths", not(emptyOrNullString())))
                                .andExpect(jsonPath("$.error.message",
                                                containsString("absolute JSONPath")));
        }

        @Test
        void postDiscoverWithCatalogFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_catalog_only.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithItemFilterCcs2Only_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_connector_ccs2_only.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscoverWithOfferPriceFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_offer_by_price.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        private String readFixture(String fileName) {
                try {
                        return Files.readString(REQUEST_FIXTURES.resolve(fileName));
                } catch (IOException e) {
                        throw new IllegalStateException("Failed to read request fixture: " + fileName, e);
                }
        }

        // ========== Registry Authorization Tests ==========

        @Autowired
        private org.beckn.discover.config.DiscoveryProperties discoveryProperties;

        @Test
        void postDiscover_WithRegistryAuthDisabled_ReturnsAck() throws Exception {
                // Registry auth is disabled by default in test profile.
                // POST is async — should succeed without Authorization header and return ACK.
                String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                ResultActions result = mockMvc.perform(post("/beckn/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$.ack_status").value("ACK"))
                                .andExpect(jsonPath("$.transaction_id").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void postDiscover_WithRegistryAuthEnabled_MissingAuthHeader_Returns400() throws Exception {
                // Enable registry auth for this test
                boolean originalEnabled = discoveryProperties.getRegistryAuth().isEnabled();
                discoveryProperties.getRegistryAuth().setEnabled(true);

                try {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // When registry auth is enabled and Authorization header is missing
                        // Expected: 400 Bad Request with NACK response
                        ResultActions result = mockMvc.perform(post("/beckn/discover")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload));

                        result.andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.ack_status").value("NACK"))
                                        .andExpect(jsonPath("$.error.code").value("SEC_SIGNATURE_MISSING"))
                                        .andExpect(jsonPath("$.error.paths").value("authorization"))
                                        .andExpect(jsonPath("$.error.message",
                                                        containsString("Missing Authorization")));
                } finally {
                        // Reset configuration
                        discoveryProperties.getRegistryAuth().setEnabled(originalEnabled);
                }
        }

        @Test
        void postDiscover_WithRegistryAuthEnabled_InvalidKeyIdFormat_Returns400() throws Exception {
                // Enable registry auth for this test
                boolean originalEnabled = discoveryProperties.getRegistryAuth().isEnabled();
                discoveryProperties.getRegistryAuth().setEnabled(true);

                try {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");
                        String invalidHeader = "Signature keyId=\"invalid|format\",algorithm=\"ed25519\",headers=\"(created)\",created=\"123\",expires=\"456\",signature=\"sig\"";

                        ResultActions result = mockMvc.perform(post("/beckn/discover")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", invalidHeader)
                                        .content(payload));

                        result.andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.ack_status").value("NACK"))
                                        .andExpect(jsonPath("$.error.code").value("SEC_SIGNATURE_INVALID"))
                                        .andExpect(jsonPath("$.error.paths").value("authorization/keyId"))
                                        .andExpect(jsonPath("$.error.message",
                                                        containsString("Invalid keyId format")));
                } finally {
                        // Reset configuration
                        discoveryProperties.getRegistryAuth().setEnabled(originalEnabled);
                }
        }

        @Test
        void postDiscover_WithRegistryAuthEnabled_InvalidSignatureFormat_Returns400() throws Exception {
                // Enable registry auth for this test
                boolean originalEnabled = discoveryProperties.getRegistryAuth().isEnabled();
                discoveryProperties.getRegistryAuth().setEnabled(true);

                try {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // When registry auth is enabled and Authorization header has invalid format
                        // Expected: 400 Bad Request with NACK response
                        ResultActions result = mockMvc.perform(post("/beckn/discover")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", "InvalidFormat")
                                        .content(payload));

                        result.andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.ack_status").value("NACK"))
                                        .andExpect(jsonPath("$.error.code").value("SEC_SIGNATURE_INVALID"))
                                        .andExpect(jsonPath("$.error.paths").value("authorization"))
                                        .andExpect(jsonPath("$.error.message",
                                                        containsString("Invalid Beckn HTTP Signature format")));
                } finally {
                        // Reset configuration
                        discoveryProperties.getRegistryAuth().setEnabled(originalEnabled);
                }
        }

        @Test
        void postDiscover_WithRegistryAuthEnabled_KeyNotFound_Returns401() throws Exception {
                // Enable registry auth for this test
                boolean originalEnabled = discoveryProperties.getRegistryAuth().isEnabled();
                discoveryProperties.getRegistryAuth().setEnabled(true);

                try {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // New Logic: Registry URL is constructed from config + subscriberId +
                        // uniqueKeyId
                        // We need to provide a valid signature header format with 3-part keyId

                        // Construct a header with a keyId that will result in a 404 from the registry
                        // keyId format: subscriber_id|unique_key_id|algorithm
                        String subscriberId = "unknown-subscriber";
                        String uniqueKeyId = "unknown-key";
                        String algorithm = "ed25519";
                        String paramKeyId = subscriberId + "|" + uniqueKeyId + "|" + algorithm;

                        long now = System.currentTimeMillis() / 1000;
                        String header = String.format(
                                        "Signature keyId=\"%s\",algorithm=\"ed25519\",headers=\"(created)\",created=\"%d\",expires=\"%d\",signature=\"abc123invalid\"",
                                        paramKeyId, now, now + 100);

                        // Expected: 401 Unauthorized with NACK response and SEC_KEY_NOT_FOUND code
                        ResultActions result = mockMvc.perform(post("/beckn/discover")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", header)
                                        .content(payload));

                        result.andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                                        .andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$.ack_status").value("NACK"))
                                        .andExpect(jsonPath("$.error.code").value("SEC_KEY_NOT_FOUND"));
                        // .andExpect(jsonPath("$.error.message", containsString("Failed to fetch public
                        // key")));
                } finally {
                        // Reset configuration
                        discoveryProperties.getRegistryAuth().setEnabled(originalEnabled);
                }
        }
}
