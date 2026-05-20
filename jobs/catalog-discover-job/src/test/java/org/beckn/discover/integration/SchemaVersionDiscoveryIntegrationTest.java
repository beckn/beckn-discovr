package org.beckn.discover.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Resource;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Beckn Item v2.1 schema support in the catalog-discover-job.
 *
 * All stored payloads use v2.1 (unprefixed) format — the upstream API rejects
 * old v2.0 (beckn: prefixed) payloads before they reach the pipeline.
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

    private static final String CAT_ID  = "cat-schema-test";
    private static final String PROV_ID = "prov-schema-test";
    private static final String BPP_ID  = "bpp-schema-test.example.com";
    private static final String BPP_URI = "https://bpp-schema-test.example.com";
    private static final String CONTEXT_URL = "https://raw.githubusercontent.com/beckn/protocol-specifications-new/"
            + "refs/heads/draft/schema/EvChargingService/v1/context.jsonld";

    @BeforeEach
    void cleanSchemaTestRows() {
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
            ps.setString(4, "Catalog");
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
            ps.setString(4, "Provider");
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

    private String v21ItemPayload(String itemId, String itemName) {
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
                      "resources": [
                        {
                          "@context": "%s",
                          "id": "%s",
                          "descriptor": {
                            "name": "%s",
                            "shortDesc": "Next-gen charger"
                          },
                          "provider": {"id": "%s"},
                          "resourceAttributes": {
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
                """, CONTEXT_URL, CAT_ID, BPP_ID, BPP_URI, CONTEXT_URL, itemId, itemName, PROV_ID, CONTEXT_URL);
    }

    private DiscoverRequest buildRequest(String transactionId, String schemaContextUrl) {
        Context ctx = new Context();
        ctx.setTransactionId(transactionId);
        ctx.setMessageId(UUID.randomUUID().toString());
        ctx.setBapId("bap.test.example.com");
        ctx.setBapUri("https://bap.test.example.com/callback");
        ctx.setAction("discover");
        ctx.setVersion("2.0.0");
        ctx.setTimestamp(OffsetDateTime.of(2026, 3, 22, 10, 0, 0, 0, ZoneOffset.UTC));
        ctx.setNetworkId("bap.net/ev-charging");

        DiscoverRequest request = new DiscoverRequest();
        request.setContext(ctx);
        request.setFilters("$.catalogs[*].resources[*]");
        if (schemaContextUrl != null) {
            request.getMessage().getIntent().setSchemaContext(List.of(schemaContextUrl));
        }
        return request;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void discoverV21Item_returnedWithCorrectFields_noSchemaVersionInResponse() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-v21-001", "2.1", v21ItemPayload("schema-test-v21-001", "v2.1 CCS2 Charger"));

        DiscoverRequest request = buildRequest("tx-schema-v21-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getContext().getAction()).isEqualTo("on_discover");
        assertThat(response.getContext().getTransactionId()).isEqualTo("tx-schema-v21-001");

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        assertThat(catalog.getResources()).isNotEmpty();

        Resource resource = catalog.getResources().stream()
                .filter(i -> "schema-test-v21-001".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item schema-test-v21-001 not found"));

        assertThat(resource.getId()).isEqualTo("schema-test-v21-001");
        assertThat(resource.getDescriptor()).isNotNull();
        assertThat(resource.getDescriptor().getName()).isEqualTo("v2.1 CCS2 Charger");
        assertThat(resource.getResourceAttributes()).isNotNull();
        assertThat(resource.getResourceAttributes().getType()).isEqualTo("ChargingService");

        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson)
                .as("schema_version must not appear anywhere in the on_discover response JSON")
                .doesNotContain("schema_version");
        assertThat(responseJson)
                .as("legacy beckn: prefixed field keys must not appear in the response")
                .doesNotContain("\"beckn:items\"")
                .doesNotContain("\"beckn:descriptor\"")
                .doesNotContain("\"beckn:provider\"")
                .doesNotContain("\"beckn:offers\"");
    }

    @Test
    void discoverV21Item_constraintsAndPoliciesPresent_noSchemaVersionInResponse() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-v21-002", "2.1", v21ItemPayload("schema-test-v21-002", "v2.1 Smart Charger"));

        DiscoverRequest request = buildRequest("tx-schema-v21-002", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getContext().getAction()).isEqualTo("on_discover");

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        Resource resource = catalog.getResources().stream()
                .filter(i -> "schema-test-v21-002".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item schema-test-v21-002 not found"));

        assertThat(resource.getId()).isEqualTo("schema-test-v21-002");
        assertThat(resource.getDescriptor().getName()).isEqualTo("v2.1 Smart Charger");
        assertThat(resource.getResourceAttributes()).isNotNull();
        assertThat(resource.getResourceAttributes().getType()).isEqualTo("ChargingService");

        assertThat(resource.getConstraints())
                .as("v2.1 item must have constraints in on_discover response")
                .isNotNull()
                .isNotEmpty();
        assertThat(resource.getPolicies())
                .as("v2.1 item must have policies in on_discover response")
                .isNotNull()
                .isNotEmpty();

        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson)
                .as("schema_version must not appear anywhere in the on_discover response JSON")
                .doesNotContain("schema_version");
    }

    @Test
    void discoverMultipleV21Items_allReturnedCorrectly() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-item-a", "2.1", v21ItemPayload("schema-test-item-a", "CCS2 Charger"));
        insertItem("schema-test-item-b", "2.1", v21ItemPayload("schema-test-item-b", "Type2 Charger"));

        DiscoverRequest request = buildRequest("tx-schema-multi-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response).isNotNull();

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Catalog not found: " + CAT_ID));

        List<String> itemIds = catalog.getResources().stream()
                .map(Resource::getId).toList();
        assertThat(itemIds)
                .as("Both resources must be returned")
                .contains("schema-test-item-a", "schema-test-item-b");

        Resource resourceA = catalog.getResources().stream()
                .filter(i -> "schema-test-item-a".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        Resource resourceB = catalog.getResources().stream()
                .filter(i -> "schema-test-item-b".equals(i.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(resourceA.getDescriptor().getName()).isEqualTo("CCS2 Charger");
        assertThat(resourceA.getResourceAttributes().getType()).isEqualTo("ChargingService");
        assertThat(resourceA.getConstraints()).isNotNull().isNotEmpty();

        assertThat(resourceB.getDescriptor().getName()).isEqualTo("Type2 Charger");
        assertThat(resourceB.getResourceAttributes().getType()).isEqualTo("ChargingService");

        String responseJson = objectMapper.writeValueAsString(response);
        assertThat(responseJson).doesNotContain("schema_version");
        assertThat(responseJson)
                .doesNotContain("\"beckn:items\"")
                .doesNotContain("\"beckn:descriptor\"")
                .doesNotContain("\"beckn:provider\"")
                .doesNotContain("\"beckn:offers\"");
    }

    @Test
    void discoverV21Item_providerExtractedCorrectly() throws Exception {
        insertCatalog();
        insertProvider();
        insertItem("schema-test-prov-001", "2.1", v21ItemPayload("schema-test-prov-001", "Provider Test Charger"));

        DiscoverRequest request = buildRequest("tx-schema-prov-001", CONTEXT_URL);
        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        List<Catalog> catalogs = response.getCatalogs();
        Catalog catalog = catalogs.stream()
                .filter(c -> CAT_ID.equals(c.getId()))
                .findFirst()
                .orElseThrow();

        Resource resource = catalog.getResources().stream()
                .filter(i -> "schema-test-prov-001".equals(i.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(resource.getProvider())
                .as("Provider must be populated for v2.1 item")
                .isNotNull();
        assertThat(resource.getProvider().getId()).isEqualTo(PROV_ID);
        assertThat(resource.getResourceAttributes().getType()).isEqualTo("ChargingService");
    }
}
