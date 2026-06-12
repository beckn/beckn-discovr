package org.beckn.discover.integration;

import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for F-14 (multi-pair schemaContext cross-leak / spec SC-45) on the
 * live PostgreSQL JSONPath path.
 *
 * <p>Seeds four items that share the same payload (so a single JSONPath filter matches
 * all of them) but differ in {@code (context_url, type)}:</p>
 * <ul>
 *   <li>I1 — (Grocery, GroceryResource)  → matches pair 1</li>
 *   <li>I2 — (Retail,  RetailResource)   → matches pair 2</li>
 *   <li>I3 — (Grocery, RetailResource)   → cross-pair, must be excluded</li>
 *   <li>I4 — (Retail,  GroceryResource)  → cross-pair, must be excluded</li>
 * </ul>
 *
 * <p>A request scoped to the two pairs {@code Grocery#GroceryResource} and
 * {@code Retail#RetailResource} must return only I1 and I2. Before the fix, the two
 * independent {@code IN} clauses also matched I3 and I4.</p>
 */
class SchemaContextPairingIntegrationTest extends BaseIntegrationTest {

    private static final String CAT = "catalog-schema-pairing-001";
    private static final String GROCERY_CTX = "https://schema.beckn.io/Grocery";
    private static final String RETAIL_CTX  = "https://schema.beckn.io/Retail";

    // JSONPath selection path present in every seeded payload → matches all four items.
    private static final String FILTER_MATCHES_ALL = "$.catalogs[*].resources[*]";

    private void seedItem(String id, String contextUrl, String type) {
        String payload = "{\"catalogs\":[{\"id\":\"" + CAT + "\",\"resources\":[{\"id\":\"" + id
                + "\",\"resourceAttributes\":{\"@context\":\"" + contextUrl + "\",\"@type\":\"" + type + "\"}}]}]}";
        jdbcTemplate.update(
                "INSERT INTO item (id, catalog_id, context_url, type, offer_ids, payload, created_by, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, ARRAY[]::TEXT[], ?::jsonb, 'test', 'test', now()) "
                        + "ON CONFLICT (id, catalog_id) DO UPDATE SET "
                        + "context_url = EXCLUDED.context_url, type = EXCLUDED.type, payload = EXCLUDED.payload",
                id, CAT, contextUrl, type, payload);
    }

    private QueryRequest request(List<String> rawSchemaContextUrls) {
        // Only filters + rawSchemaContextUrls matter for executeJsonPathQuery.
        return new QueryRequest(
                "tx-f14", "msg-f14", FILTER_MATCHES_ALL,
                List.of(),            // spatial
                null,                 // textSearch
                List.of(),            // schemaTypes (unused by paired path)
                List.of(),            // schemaContextUrls (unused by paired path)
                rawSchemaContextUrls);
    }

    @Autowired
    private PostgreSQLService postgreSQLService;

    @Test
    void multiPairSchemaContext_excludesCrossPairItems() throws Exception {
        seedItem("F14-I1", GROCERY_CTX, "GroceryResource");
        seedItem("F14-I2", RETAIL_CTX,  "RetailResource");
        seedItem("F14-I3", GROCERY_CTX, "RetailResource");   // cross-pair
        seedItem("F14-I4", RETAIL_CTX,  "GroceryResource");  // cross-pair

        // Control: no schema filter → the JSONPath filter alone matches all four.
        List<Map<String, Object>> all = postgreSQLService.executeJsonPathQuery(request(List.of()));
        assertThat(all).extracting(r -> r.get("id"))
                .as("filter matches all four seeded items when no schema filter is applied")
                .contains("F14-I1", "F14-I2", "F14-I3", "F14-I4");

        // Scoped to two pairs → only exact-pair items survive.
        List<Map<String, Object>> rows = postgreSQLService.executeJsonPathQuery(request(List.of(
                GROCERY_CTX + "#GroceryResource",
                RETAIL_CTX + "#RetailResource")));

        assertThat(rows).extracting(r -> r.get("id"))
                .as("cross-pair items (Grocery+Retail-type, Retail+Grocery-type) must be excluded")
                .contains("F14-I1", "F14-I2")
                .doesNotContain("F14-I3", "F14-I4");
    }
}
