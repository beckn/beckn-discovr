package org.beckn.catalogpublish.indexing.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.service.geometry.GeoShapeExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogDocumentAssembler}.
 * Verifies that new ES fields (item_attributes_type, item_attributes_context,
 * constraints, policies, schema_version) are populated correctly for both
 * Beckn Item v2.0 (beckn: prefixed) and v2.1 (unprefixed) payloads.
 *
 * NOTE: The assembler receives the already-normalized payload from ElasticIndexStep
 * (via PersistenceStep), so both v2.0 and v2.1 items arrive with unprefixed field names.
 * The schema_version comes from Item.getSchemaVersion().
 */
@ExtendWith(MockitoExtension.class)
class CatalogDocumentAssemblerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Mock
    private GeoShapeExtractor geoShapeExtractor;

    private CatalogDocumentAssembler assembler;

    @BeforeEach
    void setup() {
        when(geoShapeExtractor.extractGeoShapes(any())).thenReturn(Map.of());
        assembler = new CatalogDocumentAssembler(OM, geoShapeExtractor);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal denormalized payload (the shape stored in the DB / passed
     * to ElasticIndexStep), wrapping one item in catalogs[0].items[0].
     * The payload always uses unprefixed field names because PersistenceStep has
     * already normalized the item node for field extraction, but stores the original
     * payload. For the purpose of the assembler test we use canonical form.
     */
    private JsonNode buildPayload(String itemJson) throws Exception {
        return OM.readTree(String.format("""
                {
                  "catalogs": [
                    {
                      "id": "cat-1",
                      "bppId": "bpp.example.com",
                      "bppUri": "https://bpp.example.com",
                      "descriptor": {"name": "Test Catalog"},
                      "items": [%s],
                      "offers": []
                    }
                  ]
                }
                """, itemJson));
    }

    // ── item_attributes_type and item_attributes_context ─────────────────────

    @Test
    void assemble_v20NormalizedItem_populatesItemAttributesTypeAndContext() throws Exception {
        // The payload received by the assembler is the stored payload.
        // For a v2.0 item that was stored as-is (original beckn: prefixed),
        // the assembler processes whatever field names are in the stored payload.
        // Here we test with the canonical (normalized) form since ElasticIndexStep
        // normalizes before calling the assembler.
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Item",
                  "@context": "https://schema.beckn.io/item/v2.0/",
                  "id": "item-001",
                  "descriptor": {"name": "EV Charger"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {
                    "@context": "https://example.org/charging.jsonld",
                    "@type": "ChargingService",
                    "powerKw": 60
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        assertThat(doc.get("item_attributes_type")).isEqualTo("ChargingService");
        assertThat(doc.get("item_attributes_context")).isEqualTo("https://example.org/charging.jsonld");
    }

    @Test
    void assemble_v21Item_populatesItemAttributesTypeAndContext() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "@context": "https://schema.beckn.io/",
                  "id": "item-v21-001",
                  "descriptor": {"name": "Smart Meter"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {
                    "@context": "https://example.org/meter.jsonld",
                    "@type": "SmartMeter",
                    "resolution": "1kW"
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "SmartMeter");

        assertThat(doc.get("item_attributes_type")).isEqualTo("SmartMeter");
        assertThat(doc.get("item_attributes_context")).isEqualTo("https://example.org/meter.jsonld");
    }

    // ── schema_version ────────────────────────────────────────────────────────

    @Test
    void assemble_withSchemaVersion20_setsSchemaVersion() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Item",
                  "id": "item-v20",
                  "descriptor": {"name": "Item V20"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "ServiceItem", "@context": "https://ctx"}
                }
                """);

        // Use the Item-carrying overload: assemble(Item, JsonNode, schemaType, networkId)
        // For unit tests we call the payload-only overload which defaults to "2.0"
        Map<String, Object> doc = assembler.assemble(payload, "ServiceItem");

        assertThat(doc.get("schema_version")).isEqualTo("2.0");
    }

    @Test
    void assemble_withSchemaVersion21ViaItemOverload_setsSchemaVersion() throws Exception {
        // Build a minimal Item entity with schema_version = "2.1"
        org.beckn.catalogpublish.dto.CatalogContext ctx =
                new org.beckn.catalogpublish.dto.CatalogContext("bpp.test", "https://bpp.test", null, null);
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-v21", "{}", new String[0], ctx, "cat-1",
                "v2.1 Item", "SmartMeter", "prov-1", null, "2.1");

        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "id": "item-v21",
                  "descriptor": {"name": "v2.1 Item"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "SmartMeter", "@context": "https://example.org"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(item, payload, "SmartMeter", "net-1");

        assertThat(doc.get("schema_version")).isEqualTo("2.1");
    }

    // ── constraints and policies ──────────────────────────────────────────────

    @Test
    void assemble_v21ItemWithConstraints_populatesConstraintsField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "id": "item-v21",
                  "descriptor": {"name": "Charging Service"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "ChargingService", "@context": "https://ctx"},
                  "constraints": [
                    {"type": "location", "value": "Bangalore"},
                    {"type": "time", "value": "09:00-21:00"}
                  ]
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        assertThat(doc.containsKey("constraints")).isTrue();
        Object constraintsRaw = doc.get("constraints");
        assertThat(constraintsRaw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> constraints = (List<Object>) constraintsRaw;
        assertThat(constraints).hasSize(2);
    }

    @Test
    void assemble_v21ItemWithPolicies_populatesPoliciesField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "id": "item-v21",
                  "descriptor": {"name": "Charging Service"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "ChargingService", "@context": "https://ctx"},
                  "policies": [
                    {"type": "cancellation", "terms": "No refunds"},
                    {"type": "payment", "terms": "Prepaid only"}
                  ]
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        assertThat(doc.containsKey("policies")).isTrue();
        @SuppressWarnings("unchecked")
        List<Object> policies = (List<Object>) doc.get("policies");
        assertThat(policies).hasSize(2);
    }

    @Test
    void assemble_itemWithNoConstraintsOrPolicies_fieldsAbsent() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "id": "item-no-constraints",
                  "descriptor": {"name": "Plain Item"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.containsKey("constraints")).isFalse();
        assertThat(doc.containsKey("policies")).isFalse();
    }

    // ── full_text_blob includes constraints and policies text ─────────────────

    @Test
    void assemble_v21ItemWithConstraintsAndPolicies_textBlobIncludesTerms() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "Item",
                  "id": "item-text-blob",
                  "descriptor": {"name": "Slot Booking"},
                  "provider": {"id": "prov-1"},
                  "itemAttributes": {"@type": "BookingService", "@context": "https://ctx"},
                  "constraints": [{"type": "location", "value": "Mumbai"}],
                  "policies": [{"type": "cancellation", "terms": "24h notice required"}]
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "BookingService");

        String blob = (String) doc.get("full_text_blob");
        assertThat(blob).contains("Slot Booking");
        assertThat(blob).contains("Mumbai");
        assertThat(blob).contains("24h notice required");
    }
}
