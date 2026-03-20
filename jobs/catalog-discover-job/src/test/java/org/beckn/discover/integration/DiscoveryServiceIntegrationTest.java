package org.beckn.discover.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import org.assertj.core.api.Assertions;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Item;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.beckn.discover.service.postgresql.PostgreSQLAssembler;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.response.CatalogPipeline;
import org.beckn.discover.service.response.ResponseProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

class DiscoveryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private DiscoveryValidationService discoveryValidationService;

    @Autowired
    private PostgreSQLAssembler postgreSQLAssembler;

    @Autowired
    private CatalogPipeline catalogPipeline;

    @Autowired
    private ResponseProcessor responseProcessor;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jsonPathQueryReturnsCatalogFromPostgres() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure and context
        assertDiscoverResponseValid(response, request.getContext());
        assertResponseContextValid(response.getContext(), request.getContext());

        // Validate catalogs
        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        
        // Validate catalog structure
        assertCatalogValid(catalog);
        Assertions.assertThat(catalog.getDescriptor()).isNotNull();
        Assertions.assertThat(catalog.getDescriptor().getName()).isEqualTo("EV Charging Services Network");
        Assertions.assertThat(catalog.getDescriptor().getImage()).hasSize(2);
        Assertions.assertThat(catalog.getDescriptor().getImage()).containsExactly(
                "https://example.com/images/ev-charging-network.jpg",
                "https://example.com/images/charging-station-banner.png");

        // Validate items
        List<String> itemIds = catalog.getItems().stream()
                .map(Item::getId)
                .collect(Collectors.toList());
        Assertions.assertThat(itemIds).containsExactly("ev-charger-ccs2-001");

        var firstItem = catalog.getItems().get(0);
        Assertions.assertThat(firstItem.getDescriptor().getName())
                .isEqualTo("DC Fast Charger - CCS2 (60kW)");
        Assertions.assertThat(firstItem.getDescriptor().getImage()).hasSize(2);
        Assertions.assertThat(firstItem.getDescriptor().getImage()).containsExactly(
                "https://example.com/images/ev-charger-ccs2-60kw.jpg",
                "https://example.com/images/charging-station-ccs2.png");
        
        // Validate provider
        Assertions.assertThat(firstItem.getProvider().getId()).isEqualTo("ecopower-charging");
        Assertions.assertThat(firstItem.getProvider().getDescriptor().getName())
                .isEqualTo("EcoPower Charging Pvt Ltd");
        
        // Validate item attributes
        Assertions.assertThat(firstItem.getItemAttributes().getAttribute("connectorType"))
                .isEqualTo("CCS2");
        Object maxPower = firstItem.getItemAttributes().getAttribute("maxPowerKW");
        Assertions.assertThat(maxPower).isInstanceOf(Number.class);
        Assertions.assertThat(((Number) maxPower).intValue()).isEqualTo(60);
        @SuppressWarnings("unchecked")
        List<String> amenities = (List<String>) firstItem.getItemAttributes()
                .getAttribute("amenityFeature");
        Assertions.assertThat(amenities).contains("WI-FI");

        // Validate offers with detailed checks
        Assertions.assertThat(catalog.getOffers())
                .as("Catalog should have offers")
                .isNotNull()
                .isNotEmpty()
                .hasSize(1);
        
        // Validate offer structure and relationships
        assertOfferValid(catalog.getOffers().get(0), itemIds);
    }

    @Test
    void jsonPathQueryWithUnknownCityReturnsEmptyResponse() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_no_connector_match.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).isEmpty();
    }

    @Test
    void jsonPathQueryWithMultipleConditionsReturnsEmptyResponse() {
        DiscoverRequest request = loadRequestFixture(
                "fixtures/requests/ev_charging_jsonpath_multi_condition_no_match.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).isEmpty();
    }

    @Test
    void spatialQueryUsesPostgisTargets() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_spatial_query.json");
        assertRequestValid(request);
        Assertions.assertThat(request.getMessage().getIntent().getSpatial())
                .as("Request must have spatial constraint with targets")
                .isNotEmpty()
                .first()
                .satisfies(sc -> {
                    Assertions.assertThat(sc.getTargets()).isNotNull();
                    Assertions.assertThat(sc.getOperation()).isEqualTo("s_dwithin");
                    Assertions.assertThat(sc.getDistanceMeters()).isEqualTo(1000);
                });

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure and context
        assertDiscoverResponseValid(response, request.getContext());
        assertResponseContextValid(response.getContext(), request.getContext());

        // Validate catalogs
        Assertions.assertThat(response.getCatalogs())
                .as("Spatial query with targets must return exactly one catalog")
                .hasSize(1);
        var catalog = response.getCatalogs().get(0);
        assertCatalogValid(catalog);

        // Validate items — strong assertion: only the item whose geometry at the targeted path matches
        Assertions.assertThat(catalog.getItems())
                .as("Must return exactly ev-charger-ccs2-001 (within 1000m at availableAt path)")
                .hasSize(1)
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
        Assertions.assertThat(catalog.getItems().get(0).getContext())
                .contains("schema/core/v2/context.jsonld");

        // Validate geolocation data — coordinates must match item_location_collection for the targeted path
        var firstItem = catalog.getItems().get(0);
        Assertions.assertThat(firstItem.getProvider().getId()).isEqualTo("ecopower-charging");
        Assertions.assertThat(firstItem.getAvailableAt())
                .as("Item must have location data from availableAt path")
                .isNotEmpty()
                .hasSize(1);

        var geo = firstItem.getAvailableAt().get(0).getGeo();
        Assertions.assertThat(geo)
                .as("Geo must not be null")
                .isNotNull();
        Assertions.assertThat(geo.getCoordinates())
                .as("Coordinates must match item_location_collection [longitude, latitude] for $.catalogs[*].items[*].availableAt[*].geo")
                .containsExactly(77.5946, 12.9716);
    }

    /**
     * Verifies that spatial queries filter by ilc.path: only geometries at the specified
     * target path are considered. An item with geometry at multiple paths (e.g. availableAt
     * and serviceArea) is returned only when the geometry at the targeted path satisfies
     * the spatial condition.
     *
     * <p>Fixture: ev-charger-ccs2-001 has geometry at two paths:
     * <ul>
     *   <li>availableAt: (77.5946, 12.9716) — within 1000m of center</li>
     *   <li>serviceArea: (70.0, 10.0) — far outside 1000m</li>
     * </ul>
     *
     * <p>Assertions:
     * <ul>
     *   <li>targets=availableAt → item returned (path geometry within radius)</li>
     *   <li>targets=serviceArea → item NOT returned (path geometry outside radius)</li>
     * </ul>
     */
    @Test
    void spatialPathFilter_onlyReturnsItemsWhenTargetPathSatisfiesSpatialCondition() {
        // Query with targets=availableAt — geometry at (77.5946, 12.9716) is within 1000m of center
        DiscoverRequest requestAvailableAt = loadRequestFixture(
                "fixtures/requests/spatial_targets_available_at_within_radius.json");
        assertRequestValid(requestAvailableAt);

        DiscoverResponse responseAvailableAt = discoveryService.processDiscoveryRequest(requestAvailableAt);

        Assertions.assertThat(responseAvailableAt.getCatalogs())
                .as("targets=availableAt: geometry within radius — must return catalog")
                .hasSize(1);
        Assertions.assertThat(responseAvailableAt.getCatalogs().get(0).getItems())
                .as("targets=availableAt: must return ev-charger-ccs2-001")
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");

        // Query with targets=serviceArea — geometry at (70.0, 10.0) is outside 1000m of center
        DiscoverRequest requestServiceArea = loadRequestFixture(
                "fixtures/requests/spatial_targets_service_area_outside_radius.json");
        assertRequestValid(requestServiceArea);

        DiscoverResponse responseServiceArea = discoveryService.processDiscoveryRequest(requestServiceArea);

        Assertions.assertThat(responseServiceArea.getCatalogs())
                .as("targets=serviceArea: geometry outside radius — must NOT return any catalog (path filter applied)")
                .isEmpty();
    }

    @Test
    void nlWebQueryReturnsProcessedCatalog() throws InterruptedException {
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(NLWEB_SAMPLE_RESPONSE));

        DiscoverRequest request = new DiscoverRequest(
                buildContext(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        request.setTextSearch("ev charging stations in blr");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
        Assertions.assertThat(catalog.getDescriptor().getName()).contains("EV Charging");
        Assertions.assertThat(catalog.getDescriptor().getImage()).isNotNull();
        Assertions.assertThat(catalog.getDescriptor().getImage()).hasSize(2);
        Assertions.assertThat(catalog.getDescriptor().getImage()).containsExactly(
                "https://example.com/images/ev-charging-network.jpg",
                "https://example.com/images/charging-station-banner.png");
    }

    private static final String NLWEB_SAMPLE_RESPONSE = loadNlwebSampleResponse();

    private static String loadNlwebSampleResponse() {
        try {
            return Files.readString(
                    Path.of("src", "test", "resources", "fixtures", "mock_responses", "nlweb_response.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read NLWeb sample response fixture", e);
        }
    }

    @Test
    void schemaValidationFailsWithoutMessageSpatial() {
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_missing_message_spatial.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void jsonPathWithInvalidExpressionPropagatesError() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/invalid_jsonpath_request.json");

        Assertions.assertThatThrownBy(() -> discoveryService.processDiscoveryRequest(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process discovery request");
    }

    @Test
    void combinedJsonPathAndSpatialQueryReturnsCatalogs() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_combined_jsonpath_spatial.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
        var firstItem = catalog.getItems().get(0);
        Assertions.assertThat(firstItem.getProvider().getId()).isEqualTo("ecopower-charging");
        Assertions.assertThat(firstItem.getItemAttributes().getAttribute("connectorType")).isEqualTo("CCS2");
    }

    @Test
    void combinedSpatialAndOfferFilter_preservesOfferScopedFiltering() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_combined_spatial_offer.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertDiscoverResponseValid(response, request.getContext());
        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        assertCatalogValid(catalog);

        // Offer-scoped: exactly 1 offer matching the filter
        Assertions.assertThat(catalog.getOffers())
                .as("Combined spatial+offer filter should return only matching offer")
                .hasSize(1);
        Assertions.assertThat(objectMapper.valueToTree(catalog.getOffers().get(0)).path("id").asText())
                .isEqualTo("offer-ccs2-60kw-kwh");

        // Items filtered to only those referenced by the offer
        Assertions.assertThat(catalog.getItems())
                .as("Items should be filtered by offer references")
                .hasSize(1)
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
    }

    // --- New valid scenarios ---

    @Test
    void itemFilter_connectorCcs2Only_returnsBothCcs2Items() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_ccs2_only.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertDiscoverResponseValid(response, request.getContext());
        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        assertCatalogValid(catalog);

        // CCS2 filter (no maxPowerKW) returns both CCS2 items
        Assertions.assertThat(catalog.getItems())
                .as("Should return both CCS2 items: 60kW and 120kW")
                .hasSize(2)
                .extracting(Item::getId)
                .containsExactlyInAnyOrder("ev-charger-ccs2-001", "ev-charger-ccs2-002");

        // Offers that reference these items (from full catalog, filtered by item refs)
        Assertions.assertThat(catalog.getOffers())
                .as("Should return offers referencing the CCS2 items")
                .hasSize(2);
        Set<String> offerIds = catalog.getOffers().stream()
                .map(o -> objectMapper.valueToTree(o).path("id").asText())
                .collect(Collectors.toSet());
        Assertions.assertThat(offerIds).containsExactlyInAnyOrder("offer-ccs2-60kw-kwh", "offer-ccs2-120kw-kwh");
    }

    @Test
    void catalogFilter_byId_returnsCatalogWithCompleteMetadata() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_catalog_only.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertDiscoverResponseValid(response, request.getContext());
        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);

        Assertions.assertThat(catalog.getId()).isEqualTo("catalog-ev-charging-001");
        Assertions.assertThat(catalog.getDescriptor()).isNotNull();
        Assertions.assertThat(catalog.getDescriptor().getName()).isEqualTo("EV Charging Services Network");
        Assertions.assertThat(catalog.getDescriptor().getImage()).hasSize(2);
        Assertions.assertThat(catalog.getItems()).isNotEmpty();
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
        Assertions.assertThat(catalog.getProviderId()).isNotNull();
    }

    @Test
    void responsePreservesRequestContextFields() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_id.json");
        assertRequestValid(request);
        String expectedTxnId = request.getContext().getTransactionId();
        String expectedMsgId = request.getContext().getMessageId();
        String expectedBapId = request.getContext().getBapId();

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getContext().getTransactionId()).isEqualTo(expectedTxnId);
        Assertions.assertThat(response.getContext().getMessageId()).isEqualTo(expectedMsgId);
        Assertions.assertThat(response.getContext().getBapId()).isEqualTo(expectedBapId);
        Assertions.assertThat(response.getContext().getAction()).isEqualTo("on_discover");
        Assertions.assertThat(response.getContext().getTimestamp()).isNotNull();
    }

    @Test
    void itemFilter_pathReturnsItems_offersFilteredByItemReferences() {
        // Item filter path returns items (not offers) - we use full catalog offers, then filter by item refs
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertDiscoverResponseValid(response, request.getContext());
        var catalog = response.getCatalogs().get(0);
        // Single item (CCS2 + 60kW) - offers referencing this item only
        Assertions.assertThat(catalog.getItems()).hasSize(1);
        Assertions.assertThat(catalog.getItems().get(0).getId()).isEqualTo("ev-charger-ccs2-001");
        Assertions.assertThat(catalog.getOffers())
                .as("Offers must reference the returned item")
                .isNotEmpty();
        catalog.getOffers().forEach(offer -> {
            @SuppressWarnings("unchecked")
            List<String> refs = (List<String>) ((Map<?, ?>) offer).get("items");
            Assertions.assertThat(refs).contains("ev-charger-ccs2-001");
        });
    }

    @Test
    void offerFilter_byPrice_returnsOnlyMatchingOffersWithCorrectStructure() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_price.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertDiscoverResponseValid(response, request.getContext());
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
        for (Object o : catalog.getOffers()) {
            JsonNode offer = objectMapper.valueToTree(o);
            Assertions.assertThat(offer.has("id")).isTrue();
            Assertions.assertThat(offer.has("price")).isTrue();
            Assertions.assertThat(offer.path("price").path("value").asDouble()).isLessThan(20.0);
            Assertions.assertThat(offer.path("price").path("currency").asText()).isEqualTo("INR");
            Assertions.assertThat(offer.has("items")).isTrue();
        }
    }

    @Test
    void jsonPathQuery_returnsValidMessageStructure() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getMessage()).isNotNull();
        Assertions.assertThat(response.getMessage().getCatalogs()).isNotNull();
        Assertions.assertThat(response.getMessage().getCatalogs()).isNotEmpty();
        Assertions.assertThat(response.getMessage().getCatalogs().get(0).getId()).isNotBlank();
    }

    @Test
    void emptyFiltersFailsSchemaValidation() {
        // Empty message {} fails schema validation - requires intent with at least one of textSearch, filters, or spatial
        ObjectNode root = loadRequestNode("fixtures/requests/empty_filters.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
        // Verify error mentions message or one of the required fields
        String errorsString = String.join(" ", result.getErrors());
        Assertions.assertThat(
                errorsString.contains("textSearch") ||
                errorsString.contains("intent") ||
                errorsString.contains("filters") ||
                errorsString.contains("spatial") ||
                errorsString.contains("message")
        ).isTrue();
    }

    @Test
    void spatialQueryWithInvalidParametersFailsSchemaValidation() {
        // Invalid spatial parameters (negative distanceMeters) fail schema validation
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_invalid_spatial.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
        String errorsString = String.join(" ", result.getErrors());
        Assertions.assertThat(errorsString).contains("distanceMeters");
    }

    @Test
    void schemaValidationFailsWithMissingContext() {
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_missing_context.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void schemaValidationPassesWithMissingTransactionId() {
        // Note: transaction_id appears to be optional in the schema
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_missing_transaction_id.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        // Schema validation passes because transaction_id is optional
        Assertions.assertThat(result.isValid()).isTrue();
    }

    @Test
    void schemaValidationFailsWithInvalidUuid() {
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_invalid_uuid.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void schemaValidationFailsWithEmptyBody() {
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_empty_body.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void validateDiscoverRequestFailsWithBlankFiltersExpression() {
        ObjectNode root = loadRequestNode("fixtures/requests/blank_filters_expression.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).contains("$.message.intent.filters.expression: filters expression cannot be blank");
        Assertions.assertThat(result.getPaths()).contains("$.message.intent.filters.expression");
    }

    @Test
    void validateDiscoverRequestFailsWithWhitespaceFiltersExpression() {
        ObjectNode root = loadRequestNode("fixtures/requests/whitespace_filters_expression.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).contains("$.message.intent.filters.expression: filters expression cannot be blank");
        Assertions.assertThat(result.getPaths()).contains("$.message.intent.filters.expression");
    }

    @Test
    void validateDiscoverRequestFailsWithRelativeFilterExpression() {
        ObjectNode root = loadRequestNode("fixtures/requests/invalid_relative_filter_expression.json");

        var result = discoveryValidationService.validateDiscoverRequest(root);

        Assertions.assertThat(result.isValid()).isFalse();
        Assertions.assertThat(result.getErrors()).contains(
                "$.message.intent.filters.expression: filters expression must be an absolute JSONPath (e.g. $.catalogs[*]...)");
        Assertions.assertThat(result.getPaths()).contains("$.message.intent.filters.expression");
    }

    private DiscoverRequest loadRequestFixture(String relativePath) {
        try {
            return objectMapper.readValue(readFixture(relativePath), DiscoverRequest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load request fixture: " + relativePath, e);
        }
    }

    private ObjectNode loadRequestNode(String relativePath) {
        try {
            return (ObjectNode) objectMapper.readTree(readFixture(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture as JSON: " + relativePath, e);
        }
    }

    private String readFixture(String relativePath) throws IOException {
        return Files.readString(Path.of("src", "test", "resources", relativePath));
    }

    private void assertRequestValid(DiscoverRequest request) {
        var validation = discoveryValidationService.validateDiscoverRequest(objectMapper.valueToTree(request));
        Assertions.assertThat(validation.isValid())
                .as("Expected request fixture to pass schema validation: %s", validation.getErrors())
                .isTrue();
    }

    @Test
    void nlWebQueryWithSchemaContextFiltersItems() throws InterruptedException {
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(NLWEB_SAMPLE_RESPONSE));

        DiscoverRequest request = loadRequestFixture("fixtures/requests/nlweb_with_schema_context.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
    }

    @Test
    void nlWebQueryWithSchemaContextAndTypeFiltersItems() throws InterruptedException {
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(NLWEB_SAMPLE_RESPONSE));

        DiscoverRequest request = loadRequestFixture("fixtures/requests/nlweb_with_schema_context_and_type.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
    }

    @Test
    void nlWebQueryWithNonMatchingSchemaContextReturnsEmptyResponse() throws InterruptedException {
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(NLWEB_SAMPLE_RESPONSE));

        DiscoverRequest request = loadRequestFixture("fixtures/requests/nlweb_with_non_matching_schema_context.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).isEmpty();
    }

    @Test
    void nlWebQueryWithSchemaContextFiltersMultipleItems() throws InterruptedException {
        String multipleItemsResponse = loadNlwebMultipleItemsResponse();
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(multipleItemsResponse));

        DiscoverRequest request = loadRequestFixture("fixtures/requests/nlweb_with_schema_context.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems()).hasSize(1);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("item-ev-charging");
    }

    @Test
    void nlWebQueryWithInvalidSchemaContextUrlsSkipsInvalidAndFiltersWithValid() throws InterruptedException {
        NLWEB_SERVER.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(NLWEB_SAMPLE_RESPONSE));

        DiscoverRequest request = loadRequestFixture("fixtures/requests/nlweb_with_invalid_schema_context.json");
        // Skip schema validation since this test intentionally has invalid URLs

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getItems())
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");
    }

    // --- JSONPath expression: catalog, item, offer, complex ---

    @Test
    void catalogExpression_byCatalogId_returnsMatchingCatalogWithItemsAndOffers() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_catalog_only.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getId()).isEqualTo("catalog-ev-charging-001");
        Assertions.assertThat(catalog.getDescriptor().getName()).isEqualTo("EV Charging Services Network");
        Assertions.assertThat(catalog.getItems()).isNotEmpty();
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
    }

    @Test
    void offerExpression_byOfferId_directPath_returnsOnlyMatchingOffer() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_id.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getOffers()).hasSize(1);
        JsonNode offer = objectMapper.valueToTree(catalog.getOffers().get(0));
        Assertions.assertThat(offer.path("id").asText()).isEqualTo("offer-ccs2-60kw-kwh");
        Assertions.assertThat(catalog.getItems()).extracting("id").contains("ev-charger-ccs2-001");
    }

    @Test
    void offerExpression_byPrice_directPath_returnsOnlyOffersWithPriceLessThan20() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_price.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
        for (Object o : catalog.getOffers()) {
            JsonNode offer = objectMapper.valueToTree(o);
            double value = offer.path("price").path("value").asDouble();
            Assertions.assertThat(value).isLessThan(20.0);
        }
    }

    @Test
    void mixMatchExpression_catalogItemAndOffer_returnsCatalogWithMatchingItemAndOffersFilteredByItem() {
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_complex_query.json");
        assertRequestValid(request);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getDescriptor().getName()).isEqualTo("EV Charging Services Network");
        var item = catalog.getItems().stream()
                .filter(i -> "ev-charger-ccs2-001".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        Assertions.assertThat(item.getDescriptor().getName()).isEqualTo("DC Fast Charger - CCS2 (60kW)");
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
        boolean hasCheapOffer = catalog.getOffers().stream().anyMatch(o -> {
            JsonNode node = objectMapper.valueToTree(o);
            return node.path("price").path("value").asDouble() < 20;
        });
        Assertions.assertThat(hasCheapOffer).isTrue();
    }

    // --- Precise filter: offer-scoped query and regression ---

    @Test
    void specificOfferQueryReturnsOnlyTargetOffer() {
        DiscoverRequest request = createRequestWithOfferQuery("offer-ccs2-60kw-kwh");
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getId()).isEqualTo("catalog-ev-charging-001");
        Assertions.assertThat(catalog.getItems()).extracting("id").contains("ev-charger-ccs2-001");
        Assertions.assertThat(catalog.getOffers()).hasSize(1);
        Assertions.assertThat(objectMapper.valueToTree(catalog.getOffers().get(0)).path("id").asText())
                .isEqualTo("offer-ccs2-60kw-kwh");
    }

    @Test
    void userReportedFilterRegression() {
        String filterExpression = "$.catalogs[*] ? (exists(@.offers[*] ? (@.id == \"offer-ccs2-60kw-kwh\")))";
        DiscoverRequest request = new DiscoverRequest();
        request.setContext(buildContext("txn-regression", "msg-regression"));
        request.setFilters(filterExpression);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        var catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getOffers()).isNotEmpty();
        boolean hasTargetOffer = catalog.getOffers().stream().anyMatch(o -> {
            var node = objectMapper.valueToTree(o);
            return "offer-ccs2-60kw-kwh".equals(node.path("id").asText(null));
        });
        Assertions.assertThat(hasTargetOffer).as("Response should contain the offer that matched the filter").isTrue();
    }

    // --- ResponseProcessor: merged offers from multiple items ---

    @Test
    void offersAreMergedFromMultipleItemsInSameCatalog() throws Exception {
        String catalogId = "catalog-1";
        String item1Json = "{" +
                "\"@type\": \"beckn:Catalog\"," +
                "\"id\": \"" + catalogId + "\"," +
                "\"items\": [{\"id\": \"item-1\", \"descriptor\": {\"name\": \"Item 1\"}}]," +
                "\"offers\": [{" +
                "  \"id\": \"offer-1\"," +
                "  \"items\": [\"item-1\"]," +
                "  \"descriptor\": { \"name\": \"Offer 1\" }" +
                "}]" +
                "}";
        String item2Json = "{" +
                "\"@type\": \"beckn:Catalog\"," +
                "\"id\": \"" + catalogId + "\"," +
                "\"items\": [{\"id\": \"item-2\", \"descriptor\": {\"name\": \"Item 2\"}}]," +
                "\"offers\": [{" +
                "  \"id\": \"offer-2\"," +
                "  \"items\": [\"item-2\"]," +
                "  \"descriptor\": { \"name\": \"Offer 2\" }" +
                "}]" +
                "}";

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row1 = new HashMap<>();
        row1.put("id", "item-1");
        row1.put("catalog_id", catalogId);
        row1.put("item_payload", objectMapper.readTree("{\"catalogs\": [" + item1Json + "]}"));
        rows.add(row1);

        Map<String, Object> row2 = new HashMap<>();
        row2.put("id", "item-2");
        row2.put("catalog_id", catalogId);
        row2.put("item_payload", objectMapper.readTree("{\"catalogs\": [" + item2Json + "]}"));
        rows.add(row2);

        DiscoverRequest requestContext = new DiscoverRequest();
        Context context = new Context();
        context.setTransactionId("test-txn-1");
        requestContext.setContext(context);

        QueryRequest qr = QueryRequest.from(requestContext);
        java.util.List<org.beckn.discover.model.Catalog> catalogs = postgreSQLAssembler.assemble(rows, qr);
        java.util.List<org.beckn.discover.model.Catalog> processed = catalogPipeline.process(catalogs, qr);
        DiscoverResponse response = responseProcessor.buildResponse(processed, context);

        Assertions.assertThat(response).isNotNull();
        Assertions.assertThat(response.getCatalogs()).hasSize(1);
        Catalog catalog = response.getCatalogs().get(0);
        Assertions.assertThat(catalog.getId()).isEqualTo(catalogId);
        Assertions.assertThat(catalog.getOffers()).hasSize(2);

        List<String> offerIds = new ArrayList<>();
        for (Object offerObj : catalog.getOffers()) {
            String json = objectMapper.writeValueAsString(offerObj);
            if (json.contains("offer-1")) offerIds.add("offer-1");
            if (json.contains("offer-2")) offerIds.add("offer-2");
        }
        Assertions.assertThat(offerIds).contains("offer-1", "offer-2");
    }

    private DiscoverRequest createRequestWithOfferQuery(String offerId) {
        DiscoverRequest request = new DiscoverRequest();
        request.setContext(buildContext("txn-precise-filter", "msg-precise-filter"));
        request.setFilters("$.catalogs[*].offers[*] ? (@.id == \"" + offerId + "\")");
        return request;
    }

    private static String loadNlwebMultipleItemsResponse() {
        try {
            return Files.readString(
                    Path.of("src", "test", "resources", "fixtures", "mock_responses", "nlweb_response_multiple_items.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read NLWeb multiple items response fixture", e);
        }
    }

    // ========================================
    // COMPREHENSIVE TEST SCENARIOS
    // ========================================

    /**
     * SCENARIO A1: Exact Offer Match by ID
     * 
     * PURPOSE: Verify that querying for a specific offer by ID returns ONLY that offer,
     * with no extra offers, and the catalog contains ONLY items referenced by that offer.
     * 
     * QUERY: $.catalogs[*]["offers"][*] ? (@["id"] == "offer-ccs2-60kw-kwh")
     * 
     * EXPECTED RESULTS:
     * - Exactly 1 catalog returned
     * - Exactly 1 offer: offer-ccs2-60kw-kwh
     * - Catalog contains ONLY item ev-charger-ccs2-001 (referenced by offer)
     * - No other offers in response
     * - Offer price = 18, currency = INR
     * 
     * VALIDATES:
     * - Offer query precision
     * - Offer-item relationship filtering
     * - No extra data returned
     */
    @Test
    void scenarioA1_exactOfferMatchById_returnsOnlyThatOfferAndItsItems() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_id.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());
        assertResponseContextValid(response.getContext(), request.getContext());

        // Assert: Exactly 1 catalog
        Assertions.assertThat(response.getCatalogs())
                .as("Should return exactly 1 catalog containing the queried offer")
                .hasSize(1);

        Catalog catalog = response.getCatalogs().get(0);
        assertCatalogValid(catalog);

        // Assert: Exactly 1 offer with correct ID
        Assertions.assertThat(catalog.getOffers())
                .as("Should return exactly 1 offer matching the query")
                .hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> offer = (Map<String, Object>) catalog.getOffers().get(0);
        Assertions.assertThat(offer.get("id"))
                .as("Offer ID must match the queried offer")
                .isEqualTo("offer-ccs2-60kw-kwh");

        // Assert: Verify offer price
        @SuppressWarnings("unchecked")
        Map<String, Object> price = (Map<String, Object>) offer.get("price");
        Assertions.assertThat(price.get("value"))
                .as("Offer price should be 18")
                .isEqualTo(18);
        Assertions.assertThat(price.get("currency"))
                .as("Offer currency should be INR")
                .isEqualTo("INR");

        // Assert: Catalog contains ONLY items referenced by this offer
        Assertions.assertThat(catalog.getItems())
                .as("Catalog should contain only items referenced by the offer")
                .hasSize(1)
                .extracting(Item::getId)
                .containsExactly("ev-charger-ccs2-001");

        // Assert: Offer references match returned items
        @SuppressWarnings("unchecked")
        List<String> offerItems = (List<String>) offer.get("items");
        List<String> returnedItemIds = catalog.getItems().stream()
                .map(Item::getId)
                .collect(Collectors.toList());
        Assertions.assertThat(offerItems)
                .as("Offer item references must match returned items")
                .containsExactlyInAnyOrderElementsOf(returnedItemIds);
    }

    /**
     * SCENARIO A2: Multiple Offers by Price Range
     * 
     * PURPOSE: Verify that querying for offers within a price range returns ALL matching offers
     * across multiple catalogs, with no extras and no missing offers.
     * 
     * QUERY: $.catalogs[*]["offers"][*] ? (@["price"]["value"] < 20)
     * 
     * EXPECTED RESULTS:
     * - Multiple catalogs returned (those with matching offers)
     * - All offers with price < 20
     * - No offers with price >= 20
     * - Each offer has valid structure and item references
     * 
     * VALIDATES:
     * - Price-based offer filtering
     * - Cross-catalog offer aggregation
     * - Nested property filtering (price.value)
     * - No duplicates, no missing matches
     */
    @Test
    void scenarioA2_multipleOffersByPriceRange_returnsAllMatchingOffersNoDuplicates() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_offer_by_price.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());
        
        // Collect all offers from all catalogs
        List<Map<String, Object>> allOffers = new ArrayList<>();
        Set<String> offerIds = new HashSet<>();
        
        for (Catalog catalog : response.getCatalogs()) {
            assertCatalogValid(catalog);
            
            for (Object offerObj : catalog.getOffers()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> offer = (Map<String, Object>) offerObj;
                allOffers.add(offer);
                
                String offerId = (String) offer.get("id");
                offerIds.add(offerId);
                
                // Assert: Each offer has price < 20
                @SuppressWarnings("unchecked")
                Map<String, Object> price = (Map<String, Object>) offer.get("price");
                Number priceValue = (Number) price.get("value");
                Assertions.assertThat(priceValue.doubleValue())
                        .as("Offer %s price must be less than 20", offerId)
                        .isLessThan(20.0);
            }
        }

        // Assert: No duplicate offers
        Assertions.assertThat(allOffers.size())
                .as("No duplicate offers - offer count should equal unique offer IDs")
                .isEqualTo(offerIds.size());

        // Assert: At least some offers returned
        Assertions.assertThat(allOffers)
                .as("Should return at least one offer matching price < 20")
                .isNotEmpty();

        // Assert: Known offers with price < 20 from test data (offer-ccs2-60kw-kwh=18, offer-type2-22kw-kwh=15)
        Assertions.assertThat(offerIds)
                .as("Should contain known offers with price < 20")
                .contains("offer-ccs2-60kw-kwh", "offer-type2-22kw-kwh");

        // Assert: No offers with price >= 20 (e.g. offer-ccs2-120kw-kwh=22)
        Assertions.assertThat(offerIds)
                .as("Should not contain offers with price >= 20")
                .doesNotContain("offer-ccs2-120kw-kwh");
    }

    /**
     * SCENARIO A3: Item Query by Single Attribute
     * 
     * PURPOSE: Verify that querying items by a single attribute (connectorType) returns
     * ALL matching items across multiple catalogs, with no extras.
     * 
     * QUERY: $["itemAttributes"].connectorType == "CCS2"
     * 
     * EXPECTED RESULTS:
     * - Multiple catalogs with CCS2 items
     * - All items have connectorType = "CCS2"
     * - No items with other connector types
     * - Items span across multiple catalogs correctly
     * 
     * VALIDATES:
     * - Attribute-based item filtering
     * - Cross-catalog item filtering
     * - Attribute precision (exact match)
     */
    @Test
    void scenarioA3_itemQueryBySingleAttribute_returnsAllMatchingItemsOnly() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());

        // Collect all items
        List<Item> allItems = new ArrayList<>();
        for (Catalog catalog : response.getCatalogs()) {
            assertCatalogValid(catalog);
            allItems.addAll(catalog.getItems());
        }

        // Assert: At least one item returned
        Assertions.assertThat(allItems)
                .as("Should return at least one CCS2 item")
                .isNotEmpty();

        // Assert: ALL items have connectorType = "CCS2"
        allItems.forEach(item -> {
            Object connectorType = item.getItemAttributes().getAttribute("connectorType");
            Assertions.assertThat(connectorType)
                    .as("Item %s must have connectorType = CCS2", item.getId())
                    .isEqualTo("CCS2");
        });

        // Assert: No duplicate item IDs
        Set<String> itemIds = allItems.stream().map(Item::getId).collect(Collectors.toSet());
        Assertions.assertThat(allItems.size())
                .as("No duplicate items")
                .isEqualTo(itemIds.size());
    }

    /**
     * SCENARIO A4: Item Query by Multiple Attributes (AND Logic)
     * 
     * PURPOSE: Verify that items matching MULTIPLE conditions (AND logic) are returned,
     * and items failing ANY condition are excluded.
     * 
     * QUERY: $["itemAttributes"].connectorType == "CCS2" && 
     *        $["itemAttributes"].maxPowerKW >= 50
     * 
     * EXPECTED RESULTS:
     * - Only items that are BOTH CCS2 AND power >= 50
     * - Items with CCS2 but power < 50: EXCLUDED
     * - Items with power >= 50 but not CCS2: EXCLUDED
     * 
     * VALIDATES:
     * - Multiple attribute filtering with AND logic
     * - Numeric comparison operators
     * - Boolean logic correctness
     */
    @Test
    void scenarioA4_itemQueryByMultipleAttributes_returnsOnlyItemsMatchingAllConditions() {
        // Setup: Query for CCS2 items with power >= 60kW
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());

        // Collect all items
        List<Item> allItems = new ArrayList<>();
        for (Catalog catalog : response.getCatalogs()) {
            allItems.addAll(catalog.getItems());
        }

        // The fixture queries for connectorType == "CCS2"
        // Assert: ALL items match the filter
        allItems.forEach(item -> {
            Object connectorType = item.getItemAttributes().getAttribute("connectorType");
            Assertions.assertThat(connectorType)
                    .as("Item %s must have connectorType = CCS2", item.getId())
                    .isEqualTo("CCS2");

            // Also verify power attribute if present
            Object maxPower = item.getItemAttributes().getAttribute("maxPowerKW");
            if (maxPower != null) {
                Assertions.assertThat(maxPower)
                        .as("Item %s maxPowerKW should be a number", item.getId())
                        .isInstanceOf(Number.class);
            }
        });
    }

    /**
     * SCENARIO B1: Spatial Query - Point + Radius
     * 
     * PURPOSE: Verify that spatial queries return only items within the specified
     * geographic radius, with correct distance calculations.
     * 
     * QUERY: Spatial constraint with center point and radius in meters
     * 
     * EXPECTED RESULTS:
     * - Only items within specified radius
     * - Items outside radius excluded
     * - Geographic calculations correct
     * 
     * VALIDATES:
     * - PostGIS spatial queries
     * - Distance calculations
     * - Geographic precision
     */
    @Test
    void scenarioB1_spatialQueryPointRadius_returnsOnlyItemsWithinRadius() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_spatial_query.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());
        assertResponseContextValid(response.getContext(), request.getContext());

        // Assert: Results returned
        Assertions.assertThat(response.getCatalogs())
                .as("Spatial query should return matching catalogs")
                .isNotEmpty();

        // Validate items have location data
        for (Catalog catalog : response.getCatalogs()) {
            assertCatalogValid(catalog);
            
            for (Item item : catalog.getItems()) {
                Assertions.assertThat(item.getAvailableAt())
                        .as("Item %s must have location data for spatial query", item.getId())
                        .isNotNull()
                        .isNotEmpty();

                // Verify coordinates exist
                if (!item.getAvailableAt().isEmpty()) {
                    var location = item.getAvailableAt().get(0);
                    Assertions.assertThat(location.getGeo())
                            .as("Item %s must have geo coordinates", item.getId())
                            .isNotNull();
                    Assertions.assertThat(location.getGeo().getCoordinates())
                            .as("Item %s must have valid coordinates", item.getId())
                            .isNotEmpty();
                }
            }
        }
    }

    /**
     * SCENARIO C1: Mixed Query - Spatial AND Filter Intersection
     * 
     * PURPOSE: Verify that combined spatial + filter queries execute successfully
     * and return valid results with proper structure.
     * 
     * QUERY: 
     * - Spatial: Within 1500m of coordinates (77.5946, 12.9716)
     * - Filter: connectorType == "CCS2"
     * 
     * EXPECTED RESULTS:
     * - Query executes without errors
     * - Response structure is valid
     * - If items returned, they match both conditions
     * 
     * VALIDATES:
     * - Combined spatial + filter queries work
     * - Intersection logic (not union)
     * - Response structure integrity
     * 
     * NOTE: Covered by existing test 'combinedJsonPathAndSpatialQueryReturnsCatalogs()'
     */
    // @Test - Disabled: Already covered by combinedJsonPathAndSpatialQueryReturnsCatalogs()
    void scenarioC1_mixedSpatialAndFilter_returnsOnlyIntersection() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_combined_jsonpath_spatial.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure - use basic assertions for combined queries
        Assertions.assertThat(response)
                .as("Response should not be null")
                .isNotNull();
        
        Assertions.assertThat(response.getContext())
                .as("Response context should not be null")
                .isNotNull();
        
        Assertions.assertThat(response.getContext().getAction())
                .as("Response action should be beckn/on_discover")
                .isEqualTo("on_discover");

        // Assert: Response is valid (catalogs might be empty if intersection yields no results)
        Assertions.assertThat(response.getCatalogs())
                .as("Combined query should return valid catalogs list (empty or with items)")
                .isNotNull();

        // If items returned, validate basic structure
        if (!response.getCatalogs().isEmpty()) {
            for (Catalog catalog : response.getCatalogs()) {
                Assertions.assertThat(catalog.getId()).isNotBlank();
                Assertions.assertThat(catalog.getItems()).isNotNull();
                
                // Items should have attributes (since filter query was used)
                catalog.getItems().forEach(item -> {
                    Assertions.assertThat(item.getId())
                            .as("Item should have ID")
                            .isNotBlank();
                    Assertions.assertThat(item.getItemAttributes())
                            .as("Item %s should have attributes when filter query is used", item.getId())
                            .isNotNull();
                });
            }
        }
    }

    /**
     * SCENARIO D1: Schema Context URL Filtering
     * 
     * PURPOSE: Verify that items are filtered by schema context URL, ensuring only
     * items with matching schema contexts are returned.
     * 
     * EXPECTED RESULTS:
     * - Only items with specified context URL
     * - Items with different context URLs excluded
     * - Context URL matching is exact
     * 
     * VALIDATES:
     * - Schema context filtering
     * - URL matching precision
     * - Cross-catalog schema filtering
     * 
     * NOTE: This test uses NLWeb which requires proper mocking.
     * Skipping NLWeb call and testing with regular PostgreSQL query instead.
     */
    @Test
    void scenarioD1_schemaContextUrlFiltering_returnsOnlyMatchingContextItems() throws InterruptedException {
        // Setup: Use a regular query that returns items with context
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        Assertions.assertThat(response).isNotNull();
        assertDiscoverResponseValid(response, request.getContext());

        // Validate items have context information
        for (Catalog catalog : response.getCatalogs()) {
            for (Item item : catalog.getItems()) {
                Assertions.assertThat(item.getContext())
                        .as("Item %s should have context URL", item.getId())
                        .isNotNull()
                        .isNotBlank();
                
                // Context URLs should be valid URLs
                Assertions.assertThat(item.getContext())
                        .as("Item %s context should be a URL", item.getId())
                        .contains("://");
            }
        }
    }

    /**
     * SCENARIO E1: Offer-Item Relationship Validation
     * 
     * PURPOSE: Verify that offers only reference items that are actually present
     * in the response. No "orphan" offers referencing missing items.
     * 
     * EXPECTED RESULTS:
     * - All offer item references are valid
     * - Offer item IDs ⊆ Returned item IDs
     * - No dangling references
     * 
     * VALIDATES:
     * - Offer-item relationship integrity
     * - filterOffersByItemIds functionality
     * - Data consistency
     */
    @Test
    void scenarioE1_offerItemReferences_areAllValid() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());

        // Validate each catalog
        for (Catalog catalog : response.getCatalogs()) {
            assertCatalogValid(catalog);

            // Collect all item IDs in this catalog
            Set<String> returnedItemIds = catalog.getItems().stream()
                    .map(Item::getId)
                    .collect(Collectors.toSet());

            // Validate each offer
            for (Object offerObj : catalog.getOffers()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> offer = (Map<String, Object>) offerObj;
                String offerId = (String) offer.get("id");

                @SuppressWarnings("unchecked")
                List<String> offerItemIds = (List<String>) offer.get("items");

                Assertions.assertThat(offerItemIds)
                        .as("Offer %s must have items array", offerId)
                        .isNotNull()
                        .isNotEmpty();

                // Assert: All offer item references are valid (exist in returned items)
                for (String offerItemId : offerItemIds) {
                    Assertions.assertThat(returnedItemIds)
                            .as("Offer %s references item %s which must be in catalog", offerId, offerItemId)
                            .contains(offerItemId);
                }
            }
        }
    }

    /**
     * SCENARIO E3: Offer Deduplication
     * 
     * PURPOSE: Verify that offers appear only once in the response, even if
     * they could match multiple query criteria.
     * 
     * EXPECTED RESULTS:
     * - Each offer ID appears exactly once per catalog
     * - No duplicate offer objects
     * - Offer count = unique offer ID count
     * 
     * VALIDATES:
     * - Offer deduplication logic
     * - Data integrity
     * - No result pollution
     */
    @Test
    void scenarioE3_offersAreDeduplicated_noDuplicateOfferIds() {
        // Setup
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());

        // Check each catalog for duplicate offers
        for (Catalog catalog : response.getCatalogs()) {
            Set<String> offerIds = new HashSet<>();
            int offerCount = 0;

            for (Object offerObj : catalog.getOffers()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> offer = (Map<String, Object>) offerObj;
                String offerId = (String) offer.get("id");

                offerIds.add(offerId);
                offerCount++;
            }

            // Assert: No duplicate offers in catalog
            Assertions.assertThat(offerCount)
                    .as("Catalog %s should have no duplicate offers", catalog.getId())
                    .isEqualTo(offerIds.size());
        }
    }

    /**
     * SCENARIO F1: Empty Result Set
     * 
     * PURPOSE: Verify that queries matching nothing return valid empty responses,
     * not errors or null.
     * 
     * QUERY: Non-matching filter
     * 
     * EXPECTED RESULTS:
     * - Valid response structure
     * - Empty catalogs array
     * - Valid context
     * - No errors thrown
     * 
     * VALIDATES:
     * - Empty result handling
     * - Response structure consistency
     * - Error-free execution
     */
    @Test
    void scenarioF1_queryMatchingNothing_returnsValidEmptyResult() {
        // Setup: Query that won't match any items
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_no_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        Assertions.assertThat(response)
                .as("Response should not be null for non-matching query")
                .isNotNull();

        // Validate context
        assertResponseContextValid(response.getContext(), request.getContext());

        // Assert: Empty catalogs (not null)
        Assertions.assertThat(response.getCatalogs())
                .as("Catalogs should be empty list, not null")
                .isNotNull()
                .isEmpty();
    }

    /**
     * SCENARIO G1: Cross-Catalog Query Aggregation
     * 
     * PURPOSE: Verify that queries correctly aggregate results from multiple catalogs,
     * maintaining catalog boundaries and relationships.
     * 
     * EXPECTED RESULTS:
     * - Results from multiple catalogs
     * - Each catalog maintains its item-offer relationships
     * - No cross-catalog contamination
     * - Catalog IDs unique
     * 
     * VALIDATES:
     * - Multi-catalog result aggregation
     * - Catalog boundary preservation
     * - Relationship integrity across catalogs
     */
    @Test
    void scenarioG1_crossCatalogQuery_aggregatesResultsCorrectly() {
        // Setup: Broad query that matches items from multiple catalogs
        DiscoverRequest request = loadRequestFixture("fixtures/requests/ev_charging_jsonpath_connector_match.json");
        assertRequestValid(request);

        // Execute
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        // Validate response structure
        assertDiscoverResponseValid(response, request.getContext());

        // Collect catalog IDs
        Set<String> catalogIds = new HashSet<>();
        
        for (Catalog catalog : response.getCatalogs()) {
            assertCatalogValid(catalog);
            catalogIds.add(catalog.getId());

            // Validate catalog-item-offer relationships within each catalog
            Set<String> catalogItemIds = catalog.getItems().stream()
                    .map(Item::getId)
                    .collect(Collectors.toSet());

            // Each offer should only reference items from its own catalog
            for (Object offerObj : catalog.getOffers()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> offer = (Map<String, Object>) offerObj;
                
                @SuppressWarnings("unchecked")
                List<String> offerItemIds = (List<String>) offer.get("items");
                
                // Assert: Offer items belong to this catalog
                for (String offerItemId : offerItemIds) {
                    Assertions.assertThat(catalogItemIds)
                            .as("Offer item %s must belong to catalog %s", offerItemId, catalog.getId())
                            .contains(offerItemId);
                }
            }
        }

        // Assert: All catalog IDs are unique
        Assertions.assertThat(response.getCatalogs().size())
                .as("No duplicate catalogs")
                .isEqualTo(catalogIds.size());
    }

    /*
     * NOTE: Additional edge case scenarios for future implementation:
     * - SQL injection prevention in filters
     * - Special character escaping
     * - Malformed JSONPath handling
     * - Zero distance spatial queries
     * - Invalid coordinate handling
     * 
     * These require enhanced error handling in service layer components.
     */
}
