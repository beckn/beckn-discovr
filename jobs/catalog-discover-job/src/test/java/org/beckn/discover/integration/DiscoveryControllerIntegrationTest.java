package org.beckn.discover.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.beckn.discover.common.BecknFields;
import org.beckn.discover.common.ErrorCodes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.*;

@AutoConfigureMockMvc
class DiscoveryControllerIntegrationTest extends BaseIntegrationTest {

        private static final String DISCOVER_PATH = "/beckn/discover";
        private static final Path REQUEST_FIXTURES = Path.of("src", "test", "resources", "fixtures", "requests");

        @Autowired
        private MockMvc mockMvc;

        @Test
        void postDiscoverReturnsCatalogsFromService() throws Exception {
                // POST is now async: validates auth+schema, publishes to Kafka, returns ACK.
                // The actual catalog response is delivered asynchronously via response-dispatcher.
                String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void getDiscoverReturnsCatalogsFromService() throws Exception {
                String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                ResultActions result = mockMvc.perform(get(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                // Validate context fields
                                .andExpect(jsonPath("$.context").exists())
                                // Validate catalogs
                                .andExpect(jsonPath("$.message.catalogs", hasSize(1)))
                                .andExpect(jsonPath("$.message.catalogs[0].id").exists())
                                .andExpect(jsonPath("$.message.catalogs[0].descriptor.name")
                                                .value("EV Charging Services Network"))
                                .andExpect(jsonPath("$.message.catalogs[0].descriptor.mediaFile",
                                                hasSize(2)))
                                .andExpect(jsonPath("$.message.catalogs[0].descriptor.mediaFile[0].uri")
                                                .value("https://example.com/images/ev-charging-network.jpg"))
                                .andExpect(jsonPath("$.message.catalogs[0].descriptor.mediaFile[1].uri")
                                                .value("https://example.com/images/charging-station-banner.png"))
                                // Validate resources
                                .andExpect(jsonPath("$.message.catalogs[0].resources", hasSize(1)))
                                .andExpect(jsonPath("$.message.catalogs[0].resources[0].id")
                                                .value("ev-charger-ccs2-001"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].descriptor.name")
                                                .value("DC Fast Charger - CCS2 (60kW)"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].descriptor.mediaFile",
                                                hasSize(2)))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].descriptor.mediaFile[0].uri")
                                                .value("https://example.com/images/ev-charger-ccs2-60kw.jpg"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].descriptor.mediaFile[1].uri")
                                                .value("https://example.com/images/charging-station-ccs2.png"))
                                // Validate provider
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].provider.id")
                                                .value("ecopower-charging"))
                                .andExpect(jsonPath(
                                                "$.message.catalogs[0].resources[0].resourceAttributes.connectorType")
                                                .value("CCS2"))
                                // Validate offers (v2.0 uses "resourceIds" for item references)
                                .andExpect(jsonPath("$.message.catalogs[0].offers", hasSize(greaterThanOrEqualTo(1))))
                                .andExpect(jsonPath("$.message.catalogs[0].offers[0].id").exists())
                                .andExpect(jsonPath("$.message.catalogs[0].offers[0].resourceIds").isArray())
                                .andExpect(jsonPath("$.message.catalogs[0].offers[0].resourceIds[0]")
                                                .value("ev-charger-ccs2-001"));
        }

        @Test
        void postDiscoverWithInvalidSchemaReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_missing_message_spatial.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE, containsString("Schema validation failed")));
        }

        @Test
        void getDiscoverWithInvalidSchemaReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_missing_context.json");

                ResultActions result = mockMvc.perform(get(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE, containsString("Schema validation failed")));
        }

        @Test
        void postDiscoverWithMissingTransactionIdReturnsSuccess() throws Exception {
                // Context V2.0 requires transactionId — a request missing it fails schema validation.
                String payload = readFixture("invalid_missing_transaction_id.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED));
        }

        @Test
        void postDiscoverWithInvalidUuidReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_invalid_uuid.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE, anyOf(
                                                containsString(BecknFields.TRANSACTION_ID),
                                                containsString(BecknFields.MESSAGE_ID),
                                                containsString("invalid uuid"))));
        }

        @Test
        void postDiscoverWithEmptyBodyReturnsBadRequest() throws Exception {
                String payload = readFixture("invalid_empty_body.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED));
        }

        @Test
        void postDiscoverWithSpatialQueryReturnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_spatial_query.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void postDiscoverWithCombinedFiltersReturnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_combined_jsonpath_spatial.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void postDiscoverWithEmptyFiltersReturnsBadRequest() throws Exception {
                // Empty message {} fails schema validation - requires intent with at least one of
                // textSearch, filters, or spatial
                String payload = readFixture("empty_filters.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE, containsString("Schema validation failed")));
        }

        @Test
        void postDiscoverWithOfferFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_offer_by_id.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void postDiscoverWithRelativeFilterExpression_returnsBadRequest() throws Exception {
                String payload = readFixture("invalid_relative_filter_expression.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED))
                                .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE,
                                                containsString("absolute JSONPath")));
        }

        @Test
        void postDiscoverWithCatalogFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_catalog_only.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void postDiscoverWithItemFilterCcs2Only_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_connector_ccs2_only.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        @Test
        void postDiscoverWithOfferPriceFilter_returnsAck() throws Exception {
                // POST is async — request is valid, returns ACK.
                String payload = readFixture("ev_charging_jsonpath_offer_by_price.json");

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
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

                ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload));

                result.andExpect(status().isOk())
                                .andExpect(jsonPath("$." + BecknFields.STATUS).value("ACK"));
        }

        /**
         * Tests that verify Beckn Auth SDK integration is working correctly.
         * Auth is enabled via @TestPropertySource so AuthProperties record is properly initialized.
         */
        @Nested
        @TestPropertySource(properties = {
                "discovery.auth.enabled=true",
                "discovery.auth.registryBaseUrl=https://api.testnet.beckn.one/registry/dedi/lookup/",
                "discovery.auth.registryName=subscribers.beckn.one",
                "discovery.auth.registryToken=test-token",
                "discovery.auth.clockSkewSeconds=30",
                "discovery.auth.cacheTtlSeconds=2592000",
                "discovery.auth.cacheMaxKeys=100",
                "discovery.auth.timeoutSeconds=10",
                "discovery.auth.retryAttempts=3"
        })
        @AutoConfigureMockMvc
        class WithBecknAuthSDKEnabled extends BaseIntegrationTest {
                @Autowired
                private MockMvc mockMvc;

                @Test
                void postDiscover_WithRegistryAuthEnabled_MissingAuthHeader_Returns401() throws Exception {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // F-12: a missing Authorization header is an authentication failure →
                        // 401 Unauthorized with a WWW-Authenticate challenge (not 400).
                        ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload));

                        result.andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value("SEC_SIGNATURE_MISSING"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE,
                                                        containsString("Authorization header is missing")));
                }

                @Test
                void postDiscover_WithRegistryAuthEnabled_InvalidKeyIdFormat_Returns401() throws Exception {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");
                        String invalidHeader = "Signature keyId=\"invalid|format\",algorithm=\"ed25519\",headers=\"(created)\",created=\"123\",expires=\"456\",signature=\"sig\"";

                        ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", invalidHeader)
                                        .content(payload));

                        // F-12: a malformed credential is an authentication failure → 401 (not 400).
                        result.andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value("SEC_SIGNATURE_INVALID"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE,
                                                        containsString("Invalid keyId format")));
                }

                @Test
                void postDiscover_WithRegistryAuthEnabled_InvalidSignatureFormat_Returns401() throws Exception {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // F-12: a header that isn't a valid Signature credential is an authentication
                        // failure → 401 Unauthorized with a WWW-Authenticate challenge (not 400).
                        ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", "InvalidFormat")
                                        .content(payload));

                        result.andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value("SEC_SIGNATURE_INVALID"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_MESSAGE,
                                                        containsString("Authorization header format is invalid")));
                }

                @Test
                void postDiscover_WithRegistryAuthEnabled_KeyNotFound_Returns401() throws Exception {
                        String payload = readFixture("ev_charging_jsonpath_connector_match.json");

                        // Registry URL is constructed from config + subscriberId + uniqueKeyId
                        // Provide a valid signature header format with 3-part keyId
                        String subscriberId = "unknown-subscriber";
                        String uniqueKeyId = "unknown-key";
                        String algorithm = "ed25519";
                        String paramKeyId = subscriberId + "|" + uniqueKeyId + "|" + algorithm;

                        long now = System.currentTimeMillis() / 1000;
                        String header = String.format(
                                        "Signature keyId=\"%s\",algorithm=\"ed25519\",headers=\"(created)\",created=\"%d\",expires=\"%d\",signature=\"abc123invalid\"",
                                        paramKeyId, now, now + 100);

                        // Expected: 401 Unauthorized with NACK response and SEC_KEY_NOT_FOUND code
                        ResultActions result = mockMvc.perform(post(DISCOVER_PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", header)
                                        .content(payload));

                        result.andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                                        .andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$." + BecknFields.STATUS).value("NACK"))
                                        .andExpect(jsonPath("$." + BecknFields.ERROR + "." + BecknFields.ERROR_CODE).value("SEC_KEY_NOT_FOUND"));
                }
        }
}
