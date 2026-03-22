package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.common.SchemaVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BecknFieldNormalizer}.
 * Uses a real ObjectMapper (Spring Boot's default configuration is equivalent for these tests).
 */
class BecknFieldNormalizerTest {

    private static ObjectMapper om;

    @BeforeAll
    static void setup() {
        om = new ObjectMapper();
    }

    // ── detectVersion ─────────────────────────────────────────────────────────

    @Test
    void detectVersion_becknItemType_returnsV20() throws Exception {
        JsonNode item = om.readTree("""
                {"@type": "beckn:Item", "beckn:id": "i-1"}
                """);
        assertThat(BecknFieldNormalizer.detectVersion(item)).isEqualTo(SchemaVersion.V2_0);
    }

    @Test
    void detectVersion_unprefixedItemType_returnsV21() throws Exception {
        JsonNode item = om.readTree("""
                {"@type": "Item", "id": "i-1"}
                """);
        assertThat(BecknFieldNormalizer.detectVersion(item)).isEqualTo(SchemaVersion.V2_1);
    }

    @Test
    void detectVersion_missingType_defaultsToV20() throws Exception {
        JsonNode item = om.readTree("""
                {"beckn:id": "i-1"}
                """);
        assertThat(BecknFieldNormalizer.detectVersion(item)).isEqualTo(SchemaVersion.V2_0);
    }

    @Test
    void detectVersion_nullNode_defaultsToV20() {
        assertThat(BecknFieldNormalizer.detectVersion(null)).isEqualTo(SchemaVersion.V2_0);
    }

    @Test
    void detectVersion_fromTypeValue_becknItem_returnsV20() {
        assertThat(SchemaVersion.fromTypeValue("beckn:Item")).isEqualTo(SchemaVersion.V2_0);
    }

    @Test
    void detectVersion_fromTypeValue_item_returnsV21() {
        assertThat(SchemaVersion.fromTypeValue("Item")).isEqualTo(SchemaVersion.V2_1);
    }

    @Test
    void detectVersion_fromTypeValue_null_defaultsToV20() {
        assertThat(SchemaVersion.fromTypeValue(null)).isEqualTo(SchemaVersion.V2_0);
    }

    // ── normalizeItem — v2.0 prefix stripping ────────────────────────────────

    @Test
    void normalizeItem_v20_stripsAllBecknPrefixes() throws Exception {
        JsonNode item = om.readTree("""
                {
                  "@context": "https://schema.beckn.io/item/v2.0/",
                  "@type": "beckn:Item",
                  "beckn:id": "item-001",
                  "beckn:descriptor": {
                    "beckn:name": "EV Charger",
                    "beckn:shortDesc": "Fast charger"
                  },
                  "beckn:provider": {
                    "beckn:id": "provider-001"
                  }
                }
                """);

        JsonNode result = BecknFieldNormalizer.normalizeItem(item, om);

        // Top-level beckn: prefix removed
        assertThat(result.has("id")).isTrue();
        assertThat(result.get("id").asText()).isEqualTo("item-001");
        assertThat(result.has("beckn:id")).isFalse();

        // Nested descriptor — recursive normalization
        JsonNode descriptor = result.path("descriptor");
        assertThat(descriptor.isMissingNode()).isFalse();
        assertThat(descriptor.get("name").asText()).isEqualTo("EV Charger");
        assertThat(descriptor.get("shortDesc").asText()).isEqualTo("Fast charger");
        assertThat(descriptor.has("beckn:name")).isFalse();

        // Nested provider — recursive normalization
        JsonNode provider = result.path("provider");
        assertThat(provider.get("id").asText()).isEqualTo("provider-001");
        assertThat(provider.has("beckn:id")).isFalse();
    }

    @Test
    void normalizeItem_atTypeAndAtContext_neverStripped() throws Exception {
        JsonNode item = om.readTree("""
                {
                  "@context": "https://schema.beckn.io/item/v2.0/",
                  "@type": "beckn:Item",
                  "beckn:itemAttributes": {
                    "@context": "https://example.org/schema.jsonld",
                    "@type": "ElectronicItem",
                    "beckn:brand": "Acme"
                  }
                }
                """);

        JsonNode result = BecknFieldNormalizer.normalizeItem(item, om);

        // Top-level JSON-LD markers preserved exactly
        assertThat(result.get("@context").asText()).isEqualTo("https://schema.beckn.io/item/v2.0/");
        assertThat(result.get("@type").asText()).isEqualTo("beckn:Item");

        // itemAttributes normalized, but its @context and @type preserved
        JsonNode attrs = result.path("itemAttributes");
        assertThat(attrs.isMissingNode()).isFalse();
        assertThat(attrs.get("@context").asText()).isEqualTo("https://example.org/schema.jsonld");
        assertThat(attrs.get("@type").asText()).isEqualTo("ElectronicItem");
        // beckn:brand inside itemAttributes is stripped
        assertThat(attrs.get("brand").asText()).isEqualTo("Acme");
        assertThat(attrs.has("beckn:brand")).isFalse();
        // The beckn:itemAttributes key itself is stripped
        assertThat(result.has("beckn:itemAttributes")).isFalse();
    }

    @Test
    void normalizeItem_v21_idempotent_noChange() throws Exception {
        JsonNode item = om.readTree("""
                {
                  "@context": "https://schema.beckn.io/",
                  "@type": "Item",
                  "id": "item-001",
                  "descriptor": { "name": "EV Charger", "shortDesc": "Fast" },
                  "provider": { "id": "provider-001" },
                  "itemAttributes": {
                    "@context": "https://example.org/schema.jsonld",
                    "@type": "ChargingService",
                    "connectorType": "CCS2"
                  },
                  "constraints": [{"type": "location", "value": "Bangalore"}],
                  "policies": [{"type": "cancellation", "terms": "No refunds"}]
                }
                """);

        JsonNode result = BecknFieldNormalizer.normalizeItem(item, om);

        // All fields unchanged — no beckn: prefixes existed
        assertThat(result.get("id").asText()).isEqualTo("item-001");
        assertThat(result.get("@type").asText()).isEqualTo("Item");
        assertThat(result.path("descriptor").get("name").asText()).isEqualTo("EV Charger");
        assertThat(result.path("itemAttributes").get("@type").asText()).isEqualTo("ChargingService");
        assertThat(result.path("itemAttributes").get("connectorType").asText()).isEqualTo("CCS2");
        assertThat(result.path("constraints").isArray()).isTrue();
        assertThat(result.path("constraints").get(0).get("type").asText()).isEqualTo("location");
        assertThat(result.path("policies").isArray()).isTrue();
    }

    @Test
    void normalizeItem_itemAttributes_becknPrefix_strippedRecursively() throws Exception {
        JsonNode item = om.readTree("""
                {
                  "@type": "beckn:Item",
                  "beckn:itemAttributes": {
                    "@context": "https://example.org/schema.jsonld",
                    "@type": "ElectronicItem",
                    "beckn:brand": "Acme",
                    "beckn:weight": 1.5
                  }
                }
                """);

        JsonNode result = BecknFieldNormalizer.normalizeItem(item, om);

        JsonNode attrs = result.path("itemAttributes");
        assertThat(attrs.has("@context")).isTrue();
        assertThat(attrs.has("@type")).isTrue();
        assertThat(attrs.get("brand").asText()).isEqualTo("Acme");
        assertThat(attrs.get("weight").asDouble()).isEqualTo(1.5);
        assertThat(attrs.has("beckn:brand")).isFalse();
        assertThat(attrs.has("beckn:weight")).isFalse();
    }

    @Test
    void normalizeItem_nestedDescriptor_strippedRecursively() throws Exception {
        JsonNode item = om.readTree("""
                {
                  "@type": "beckn:Item",
                  "beckn:id": "item-001",
                  "beckn:descriptor": {
                    "beckn:name": "Charger",
                    "beckn:shortDesc": "Fast",
                    "beckn:longDesc": "DC fast charger",
                    "beckn:images": ["http://example.com/img.png"]
                  }
                }
                """);

        JsonNode result = BecknFieldNormalizer.normalizeItem(item, om);

        JsonNode desc = result.path("descriptor");
        assertThat(desc.get("name").asText()).isEqualTo("Charger");
        assertThat(desc.get("shortDesc").asText()).isEqualTo("Fast");
        assertThat(desc.get("longDesc").asText()).isEqualTo("DC fast charger");
        assertThat(desc.path("images").get(0).asText()).isEqualTo("http://example.com/img.png");
        assertThat(result.get("id").asText()).isEqualTo("item-001");
    }

    // ── normalizeCatalog ──────────────────────────────────────────────────────

    @Test
    void normalizeCatalog_mixedItems_eachNormalizedPerItem() throws Exception {
        JsonNode catalog = om.readTree("""
                {
                  "id": "cat-1",
                  "items": [
                    {
                      "@type": "beckn:Item",
                      "beckn:id": "item-v20",
                      "beckn:descriptor": {"beckn:name": "v2.0 Item"}
                    },
                    {
                      "@type": "Item",
                      "id": "item-v21",
                      "descriptor": {"name": "v2.1 Item"}
                    }
                  ]
                }
                """);

        JsonNode result = org.beckn.catalogpublish.util.BecknFieldNormalizer.normalizeCatalog(catalog, om);

        JsonNode items = result.path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(2);

        JsonNode item0 = items.get(0);
        assertThat(item0.get("id").asText()).isEqualTo("item-v20");
        assertThat(item0.path("descriptor").get("name").asText()).isEqualTo("v2.0 Item");
        assertThat(item0.has("beckn:id")).isFalse();

        JsonNode item1 = items.get(1);
        assertThat(item1.get("id").asText()).isEqualTo("item-v21");
        assertThat(item1.path("descriptor").get("name").asText()).isEqualTo("v2.1 Item");
    }

    // ── SchemaVersion value() ─────────────────────────────────────────────────

    @Test
    void schemaVersion_v20_value() {
        assertThat(SchemaVersion.V2_0.getValue()).isEqualTo("2.0");
    }

    @Test
    void schemaVersion_v21_value() {
        assertThat(SchemaVersion.V2_1.getValue()).isEqualTo("2.1");
    }
}
