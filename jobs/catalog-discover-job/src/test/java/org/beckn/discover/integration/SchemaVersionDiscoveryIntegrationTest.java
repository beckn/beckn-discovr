package org.beckn.discover.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Item;
import org.beckn.discover.service.DiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for dual Beckn Item v2.0 / v2.1 schema version support
 * in the catalog-discover-job.
 *
 * Each test inserts items directly into the DB then drives discovery via
 * {@link DiscoveryService} and asserts the on_discover response structure.
 * No Kafka or ES is required — tests use the PostgreSQL query engine only.
 */
class SchemaVersionDiscoveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DiscoveryService discoveryService;

    // Minimal catalog/provider/item catalog_id for these tests
    private static final String CAT_ID  = "cat-schema-test";
    private static final String PROV_ID = "prov-schema-test";
    private static final String BPP_ID  = "bpp-schema-test.example.com";
    private static final String BPP_URI = "https://bpp-schema-test.example.com";
    private static final String CONTEXT_URL = "https://raw.githubusercontent.com/beckn/protocol-specifications-new/"
            + "refs/heads/draft/schema/EvChargingService/v1/context.jsonld";

    @BeforeEach
    void cleanSchemaTestRows() {
        // Remove rows from previous test runs in this class — BaseIntegrationTest
        // loads the shared EV fixture via @BeforeAll which we keep intact.
        jdbcTemplate.execute("DELETE FROM item_location_collection WHERE item_id LIKE 'schema-test-%'");
        jdbcTemplate.execute("DELETE FROM item WHERE id LIKE 'schema-test-%'");
        jdbcTemplate.update("DELETE FROM provider WHERE id = ?", PROV_ID);
        jdbcTemplate.update("DELETE FROM catalog WHERE id = ?", CAT_ID);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertCatalog() {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO catalog (id, name, context_url, type, bpp_id, bpp_uri, payload, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING");
            ps.setString(1, CAT_ID);
            ps.setString(2, "Schema Test Catalog");
            ps.setString(3, CONTEXT_URL);
            ps.setString(4, "beckn:Catalog");
            ps.setString(5, BPP_ID);
            ps.setString(6, BPP_URI);
            ps.setObject(7, pgJsonb("{}"));
            ps.setTimestamp(8, Timestamp.from(OffsetDateTime.now().toInstant()));
            return ps;
        });
    }

    private void insertProvider() {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO provider (id, name, context_url, type, bpp_id, bpp_uri, catalog_id, payload, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING");
            ps.setString(1, PROV_ID);
            ps.setString(2, "Test Provider");
            ps.setString(3, CONTEXT_URL);
            ps.setString(4, "beckn:Provider");
            ps.setString(5, BPP_ID);
            ps.setString(6, BPP_URI);
            ps.setString(7, CAT_ID);
            ps.setObject(8, pgJsonb("{}"));
            ps.setTimestamp(9, Timestamp.from(OffsetDateTime.now().toInstant()));
            return ps;
        });
    }

    private void insertItem(String itemId, String schemaVersion, String payloadJson) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO item (id, name, context_url, type, bpp_id, bpp_uri, provider_id, catalog_id, "
                            + "payload, schema_version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload, "
                            + "schema_version = EXCLUDED.schema_version");
            ps.setString(1, itemId);
            ps.setString(2, "Test Item " + itemId);
            ps.setString(3, CONTEXT_URL);
            ps.setString(4, "ChargingService");
            ps.setString(5, BPP_ID);
            ps.setString(6, BPP_URI);
            ps.setString(7, PROV_ID);
            ps.setString(8, CAT_ID);
            ps.setObject(9, pgJsonb(payloadJson));
            ps.setString(10, schemaVersion);
            ps.setTimestamp(11, Timestamp.from(OffsetDateTime.now().toInstant()));
            return ps;
        });
    }

    private PGobject pgJsonb(String json) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json);
            return o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String v20ItemPayload(String itemId) {
        // Uses beckn: prefixed field names on item-level fields as stored by the
        // catalog-publish-job for Beckn Protocol v2.0 items. The assembler's
        // extractItemNode locates items via the unprefixed "id" key, then
        // BecknFieldNormalizer strips the beckn: prefix before DTO deserialization.
        return String.format("""
                {
                  "catalogs": [
                    {
                      "@type": "beckn:Catalog",
                      "@context": "%s",
                      "id": "%s",
                      "bppId": "%s",
                      "bppUri": "%s",
                      "descriptor": {"name": "Schema Test Catalog"},
                      "offers": [],
                      "items": [
                        {
                          "@context": "%s",
                          "@type": "beckn:Item",
                          "id": "%s",
                          "beckn:descriptor": {
                            "@type": "beckn:Descriptor",
                            "beckn:name": "v2.0 CCS2 Charger",
                            "beckn:shortDesc": "DC fast charger"
                          },
                          "beckn:provider": {"beckn:id": "%s"},
                          "beckn:itemAttributes": {
                            "@context": "%s",
                            "@type": "ChargingService",
                            "connectorType": "CCS2",
                            "powerKw": 60
                          },
                          "beckn:networkId": "bap.net/ev-charging"
                        }
                      ]
                    }
                  ]
                }
                """, CONTEXT_URL, CAT_ID, BPP_ID, BPP_URI, CONTEXT_URL, itemId, PROV_ID, CONTEXT_URL);
    }

    private String v21ItemPayload(String itemId) {
        return String.format("""
                {
                  "catalogs": [
                    {
                      "@type": "Catalog",
                      "@context": "%s",
                      "id": "%s",
                      "bppId": "%s",
                      "bppUri": "%s",
                      "descriptor": {"name": "Schema Test Catalog"},
                      "offers": [],
                      "items": [
                        {
                          "@context": "%s",
                          "@type": "Item",
                          "id": "%s",
                          "descriptor": {
                            "name": "v2.1 Smart Charger",
                            "shortDesc": "Next-gen charger"
                          },
                          "provider": {"id": "%s"},
                          "itemAttributes": {
                            "@context": "%s",
                            "@type": "ChargingService",
                            "connectorType": "CCS2",
                            "powerKw": 120
                          },
                          "constraints": [
                            {"type": "location", "value": "Bangalore"},
                            {"type": "vehicleType", "value": "EV"}
                          ],
                          "policies": [
                            {"type": "cancellation", "terms": "No refunds after session start"}
                          ],
                          "networkId": "bap.net/ev-charging"
                        }
                      ]
                    }
                  ]
                }
                """, CONTEXT_URL, CAT_ID, BPP_ID, BPP_URI, CONTEXT_URL, itemId, PROV_ID, CONTEXT_URL);
    }

    private DiscoverRequest buildRequest(String transactionId, String schemaContextUrl) {
        Context ctx = new Context();
        ctx.setTransactionId(transactionId);
        ctx.setMessageId(UUID.randomUUID().toString());
        ctx.setBapId("bap.test.example.com");
        ctx.setBapUri("https://bap.test.example.com/callback");
        ctx.setDomain("beckn.one:mobility:ev-charging:*");
        ctx.setAction("discover");
        ctx.setVersion("2.0.0");
        ctx.setTimestamp(OffsetDateTime.of(2026, 3, 22, 10, 0, 0, 0, ZoneOffset.UTC));
        ctx.setNetworkId("bap.net/ev-charging");
        if (schemaContextUrl != null) {
            ctx.setSchemaContext(List.of(schemaContextUrl));
        }

        // Use a broad JSONPath filter that matches all items in any catalog,
        // so the request routes via Path B (PostgreSQL filter query).
        // With no filters, routing goes to Path D (NLWeb text search) which
        // requires a non-null query string.
        DiscoverRequest request = new DiscoverRequest();
        request.setContext(ctx);
        request.setFilters("$.catalogs[*].items[*]");
        return request;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void discoverV20Item_returnedWithCorrectFields_noSchemaVersionInResponse() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-v20-001", "2.0", v20ItemPayload("schema-test-v20-001"));

        DiscoverRequest request = buildRequest("tx-schema-v20-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getContext().getAction()).isEqualTo("on_discover");
        assertThat(response.getContext().getTransactionId()).isEqualTo("tx-schema-v20-001");

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        assertThat(catalog.getItems()).isNotEmpty();

        Item item = catalog.getItems().stream()
                .filter(i -> "schema-test-v20-001".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item schema-test-v20-001 not found"));

        assertThat(item.getId()).isEqualTo("schema-test-v20-001");
        assertThat(item.getDescriptor()).isNotNull();
        assertThat(item.getDescriptor().getName()).isEqualTo("v2.0 CCS2 Charger");
        assertThat(item.getItemAttributes()).isNotNull();
        assertThat(item.getItemAttributes().getType()).isEqualTo("ChargingService");

        // schema_version must NOT appear in the serialized response JSON
        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson)
                .as("schema_version must not appear anywhere in the on_discover response JSON")
                .doesNotContain("schema_version");
    }

    @Test
    void discoverV21Item_constraintsAndPoliciesPresent_noSchemaVersionInResponse() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-v21-001", "2.1", v21ItemPayload("schema-test-v21-001"));

        DiscoverRequest request = buildRequest("tx-schema-v21-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getContext().getAction()).isEqualTo("on_discover");

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        Item item = catalog.getItems().stream()
                .filter(i -> "schema-test-v21-001".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item schema-test-v21-001 not found"));

        assertThat(item.getId()).isEqualTo("schema-test-v21-001");
        assertThat(item.getDescriptor().getName()).isEqualTo("v2.1 Smart Charger");
        assertThat(item.getItemAttributes()).isNotNull();
        assertThat(item.getItemAttributes().getType()).isEqualTo("ChargingService");

        // v2.1 constraints and policies must be present in the response
        assertThat(item.getConstraints())
                .as("v2.1 item must have constraints in on_discover response")
                .isNotNull()
                .isNotEmpty();
        assertThat(item.getPolicies())
                .as("v2.1 item must have policies in on_discover response")
                .isNotNull()
                .isNotEmpty();

        // schema_version must NOT appear in the serialized response JSON
        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson)
                .as("schema_version must not appear anywhere in the on_discover response JSON")
                .doesNotContain("schema_version");
    }

    @Test
    void discoverMixedCatalog_bothV20AndV21_returnedCorrectly() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-v20-mix", "2.0", v20ItemPayload("schema-test-v20-mix"));
        insertItem("schema-test-v21-mix", "2.1", v21ItemPayload("schema-test-v21-mix"));

        DiscoverRequest request = buildRequest("tx-schema-mix-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        List<String> itemIds = catalog.getItems().stream()
                .map(Item::getId).toList();
        assertThat(itemIds)
                .as("Both v2.0 and v2.1 items must be returned")
                .contains("schema-test-v20-mix", "schema-test-v21-mix");

        Item v20Item = catalog.getItems().stream()
                .filter(i -> "schema-test-v20-mix".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        Item v21Item = catalog.getItems().stream()
                .filter(i -> "schema-test-v21-mix".equals(i.getId()))
                .findFirst()
                .orElseThrow();

        // v2.0 item: descriptor name populated from normalized beckn:descriptor.beckn:name
        assertThat(v20Item.getDescriptor().getName()).isEqualTo("v2.0 CCS2 Charger");
        assertThat(v20Item.getItemAttributes().getType()).isEqualTo("ChargingService");

        // v2.1 item: descriptor name populated directly, constraints present
        assertThat(v21Item.getDescriptor().getName()).isEqualTo("v2.1 Smart Charger");
        assertThat(v21Item.getItemAttributes().getType()).isEqualTo("ChargingService");
        assertThat(v21Item.getConstraints()).isNotNull().isNotEmpty();
        assertThat(v21Item.getPolicies()).isNotNull().isNotEmpty();

        // schema_version must not appear in the response
        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson).doesNotContain("schema_version");
    }

    @Test
    void discoverV20Item_normalizationMapsFieldsCorrectly() throws Exception {
        insertCatalog();
        insertProvider();
        // Store a v2.0 item where all fields use beckn: prefix
        insertItem("schema-test-v20-norm", "2.0", v20ItemPayload("schema-test-v20-norm"));

        DiscoverRequest request = buildRequest("tx-schema-v20-norm", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow();

        Item item = catalog.getItems().stream()
                .filter(i -> "schema-test-v20-norm".equals(i.getId()))
                .findFirst()
                .orElseThrow();

        // Provider ID must be extracted correctly from v2.0 payload via normalization
        assertThat(item.getProvider())
                .as("Provider must be populated for v2.0 item")
                .isNotNull();
        assertThat(item.getProvider().getId()).isEqualTo(PROV_ID);

        // itemAttributes @type must be populated correctly
        assertThat(item.getItemAttributes().getType()).isEqualTo("ChargingService");
    }
}
