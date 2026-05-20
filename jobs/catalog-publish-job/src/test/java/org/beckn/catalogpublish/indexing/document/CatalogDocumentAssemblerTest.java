package org.beckn.catalogpublish.indexing.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.service.geometry.GeoShapeExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogDocumentAssembler}.
 * Verifies that new ES fields (item_attributes_type, item_attributes_context,
 * constraints, policies, schema_version) are populated correctly for both
 * Beckn Item v2.0 and v2.1 (unprefixed) payloads.
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
                      "provider": {"id": "prov-catalog", "descriptor": {"name": "Catalog Provider"}},
                      "resources": [%s],
                      "offers": []
                    }
                  ]
                }
                """, itemJson));
    }

    private JsonNode buildPayloadWithOffers(String itemJson, String offersJson) throws Exception {
        return OM.readTree(String.format("""
                {
                  "catalogs": [{
                    "id": "cat-1",
                    "bppId": "bpp.example.com",
                    "bppUri": "https://bpp.example.com",
                    "descriptor": {"name": "Test Catalog"},
                    "provider": {"id": "prov-catalog", "descriptor": {"name": "Catalog Provider"}},
                    "resources": [%s],
                    "offers": %s
                  }]
                }
                """, itemJson, offersJson));
    }

    // ── item_attributes_type and item_attributes_context ─────────────────────

    @Test
    void assemble_v20NormalizedItem_populatesItemAttributesTypeAndContext() throws Exception {
        // The payload received by the assembler is the stored payload.
        // For a v2.0 item that was stored as-is (original unprefixed),
        // the assembler processes whatever field names are in the stored payload.
        // Here we test with the canonical (normalized) form since ElasticIndexStep
        // normalizes before calling the assembler.
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "@context": "https://schema.beckn.io/item/v2.0/",
                  "id": "item-001",
                  "descriptor": {"name": "EV Charger"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {
                    "@context": "https://example.org/charging.jsonld",
                    "@type": "ChargingService",
                    "powerKw": 60
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        assertThat(doc.get("resource_attributes_type")).isEqualTo("ChargingService");
        assertThat(doc.get("resource_attributes_context")).isEqualTo("https://example.org/charging.jsonld");
    }

    @Test
    void assemble_v21Item_populatesItemAttributesTypeAndContext() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "@context": "https://schema.beckn.io/",
                  "id": "item-v21-001",
                  "descriptor": {"name": "Smart Meter"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {
                    "@context": "https://example.org/meter.jsonld",
                    "@type": "SmartMeter",
                    "resolution": "1kW"
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "SmartMeter");

        assertThat(doc.get("resource_attributes_type")).isEqualTo("SmartMeter");
        assertThat(doc.get("resource_attributes_context")).isEqualTo("https://example.org/meter.jsonld");
    }

    // ── schema_version ────────────────────────────────────────────────────────

    @Test
    void assemble_withSchemaVersion20_setsSchemaVersion() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-v20",
                  "descriptor": {"name": "Item V20"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "ServiceItem", "@context": "https://ctx"}
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
                "v2.1 Item", "SmartMeter", "prov-1", null, "2.1", "bpp.test", "https://bpp.test");

        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-v21",
                  "descriptor": {"name": "v2.1 Item"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "SmartMeter", "@context": "https://example.org"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(item, payload, "SmartMeter", List.of("net-1"));

        assertThat(doc.get("schema_version")).isEqualTo("2.1");
    }

    // ── constraints and policies ──────────────────────────────────────────────

    @Test
    void assemble_v21ItemWithConstraints_populatesConstraintsField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-v21",
                  "descriptor": {"name": "Charging Service"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "ChargingService", "@context": "https://ctx"},
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
                  "@type": "beckn:Resource",
                  "id": "item-v21",
                  "descriptor": {"name": "Charging Service"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "ChargingService", "@context": "https://ctx"},
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
                  "@type": "beckn:Resource",
                  "id": "item-no-constraints",
                  "descriptor": {"name": "Plain Item"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.containsKey("constraints")).isFalse();
        assertThat(doc.containsKey("policies")).isFalse();
    }

    // ── item_descriptor_thumbnail_image ───────────────────────────────────────

    @Test
    void assemble_itemWithThumbnailImage_populatesThumbnailImageField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "id": "item-thumb",
                  "descriptor": {
                    "name": "Thumb Item",
                    "thumbnailImage": "https://example.org/thumb.jpg"
                  },
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.get("resource_descriptor_thumbnail_image")).isEqualTo("https://example.org/thumb.jpg");
    }

    // ── item_descriptor_docs ──────────────────────────────────────────────────

    @Test
    void assemble_itemWithDescriptorDocs_populatesDocsField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "id": "item-docs",
                  "descriptor": {
                    "name": "Docs Item",
                    "docs": [
                      {"url": "https://example.org/doc1.pdf", "label": "Manual"},
                      {"url": "https://example.org/doc2.pdf", "label": "Spec"}
                    ]
                  },
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.containsKey("resource_descriptor_docs")).isTrue();
        @SuppressWarnings("unchecked")
        List<Object> docs = (List<Object>) doc.get("resource_descriptor_docs");
        assertThat(docs).hasSize(2);
    }

    // ── item_descriptor_media_file ────────────────────────────────────────────

    @Test
    void assemble_itemWithDescriptorMediaFile_populatesMediaFileField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "id": "item-media",
                  "descriptor": {
                    "name": "Media Item",
                    "mediaFile": [
                      {"url": "https://example.org/video.mp4", "mimetype": "video/mp4"}
                    ]
                  },
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.containsKey("resource_descriptor_media_file")).isTrue();
        @SuppressWarnings("unchecked")
        List<Object> mediaFile = (List<Object>) doc.get("resource_descriptor_media_file");
        assertThat(mediaFile).hasSize(1);
    }

    // ── catalog_provider_id / catalog_provider_name ───────────────────────────

    @Test
    void assemble_catalogWithProvider_populatesCatalogProviderFields() throws Exception {
        // catalog-level provider is in buildPayload() default payload
        JsonNode payload = buildPayload("""
                {
                  "id": "item-1",
                  "descriptor": {"name": "Item"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        // Flat fields retained for backward compatibility
        assertThat(doc.get("catalog_provider_id")).isEqualTo("prov-catalog");
        assertThat(doc.get("catalog_provider_name")).isEqualTo("Catalog Provider");

        // Full catalog_provider object
        assertThat(doc.containsKey("catalog_provider")).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> catalogProvider = (Map<String, Object>) doc.get("catalog_provider");
        assertThat(catalogProvider.get("id")).isEqualTo("prov-catalog");
        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor = (Map<String, Object>) catalogProvider.get("descriptor");
        assertThat(descriptor.get("name")).isEqualTo("Catalog Provider");
    }

    @Test
    void assemble_catalogWithFullProvider_storesCatalogProviderObject() throws Exception {
        // Custom payload with rich provider — descriptor, availableAt, providerAttributes
        JsonNode payload = OM.readTree("""
                {
                  "catalogs": [{
                    "id": "cat-1",
                    "bppId": "bpp.example.com",
                    "bppUri": "https://bpp.example.com",
                    "descriptor": {"name": "Test Catalog"},
                    "provider": {
                      "id": "prov-rich",
                      "descriptor": {
                        "name": "EcoPower Charging",
                        "shortDesc": "Clean energy provider",
                        "longDesc": "Bengaluru-based EV charging network",
                        "code": "ECO"
                      },
                      "availableAt": [
                        {
                          "id": "loc-1",
                          "geo": {"type": "Point", "coordinates": [77.5, 12.9]},
                          "address": {"addressLocality": "Bengaluru", "addressCountry": "IND"}
                        }
                      ],
                      "providerAttributes": {
                        "@context": "https://example.org/provider.jsonld",
                        "@type": "ChargingProvider",
                        "certification": "ISO-50001"
                      }
                    },
                    "resources": [{
                      "id": "item-1",
                      "descriptor": {"name": "Item"},
                      "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                    }],
                    "offers": []
                  }]
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.containsKey("catalog_provider")).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> catalogProvider = (Map<String, Object>) doc.get("catalog_provider");
        assertThat(catalogProvider.get("id")).isEqualTo("prov-rich");

        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor = (Map<String, Object>) catalogProvider.get("descriptor");
        assertThat(descriptor.get("name")).isEqualTo("EcoPower Charging");
        assertThat(descriptor.get("shortDesc")).isEqualTo("Clean energy provider");
        assertThat(descriptor.get("longDesc")).isEqualTo("Bengaluru-based EV charging network");
        assertThat(descriptor.get("code")).isEqualTo("ECO");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availableAt = (List<Map<String, Object>>) catalogProvider.get("availableAt");
        assertThat(availableAt).hasSize(1);
        assertThat(availableAt.get(0).get("id")).isEqualTo("loc-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) availableAt.get(0).get("address");
        assertThat(address.get("addressLocality")).isEqualTo("Bengaluru");

        @SuppressWarnings("unchecked")
        Map<String, Object> providerAttributes = (Map<String, Object>) catalogProvider.get("providerAttributes");
        assertThat(providerAttributes.get("@context")).isEqualTo("https://example.org/provider.jsonld");
        assertThat(providerAttributes.get("@type")).isEqualTo("ChargingProvider");
        assertThat(providerAttributes.get("certification")).isEqualTo("ISO-50001");

        // Flat fields remain populated for backward compatibility
        assertThat(doc.get("catalog_provider_id")).isEqualTo("prov-rich");
        assertThat(doc.get("catalog_provider_name")).isEqualTo("EcoPower Charging");
    }

    // ── item_rating_review_text ───────────────────────────────────────────────

    @Test
    void assemble_itemWithRatingReviewText_populatesReviewTextField() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "id": "item-review",
                  "descriptor": {"name": "Reviewed Item"},
                  "provider": {"id": "prov-1"},
                  "rating": {
                    "ratingValue": 4.8,
                    "reviewText": "Excellent service and fast charging"
                  },
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        assertThat(doc.get("resource_rating_review_text")).isEqualTo("Excellent service and fast charging");
    }

    // ── network_id as List ────────────────────────────────────────────────────

    @Test
    void assemble_itemWithArrayNetworkIds_populatesNetworkIdAsList() throws Exception {
        org.beckn.catalogpublish.dto.CatalogContext ctx =
                new org.beckn.catalogpublish.dto.CatalogContext("bpp.net", "https://bpp.net",
                        new String[]{"net-a", "net-b"}, null);
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-multi-net", "{}", new String[0], ctx, "cat-1",
                "Multi Net Item", "GenericItem", "prov-1", null, "2.0", "bpp.net", "https://bpp.net");

        JsonNode payload = buildPayload("""
                {
                  "id": "item-multi-net",
                  "descriptor": {"name": "Multi Net Item"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(item, payload, "GenericItem", Arrays.asList("net-a", "net-b"));

        assertThat(doc.get("network_id")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> networkId = (List<String>) doc.get("network_id");
        assertThat(networkId).containsExactly("net-a", "net-b");
    }

    @Test
    void assemble_itemWithSingleNetworkId_populatesNetworkIdAsListOfOne() throws Exception {
        org.beckn.catalogpublish.dto.CatalogContext ctx =
                new org.beckn.catalogpublish.dto.CatalogContext("bpp.single", "https://bpp.single",
                        new String[]{"net-only"}, null);
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-single-net", "{}", new String[0], ctx, "cat-1",
                "Single Net Item", "GenericItem", "prov-1", null, "2.0", "bpp.single", "https://bpp.single");

        JsonNode payload = buildPayload("""
                {
                  "id": "item-single-net",
                  "descriptor": {"name": "Single Net Item"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(item, payload, "GenericItem", List.of("net-only"));

        assertThat(doc.get("network_id")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> networkId = (List<String>) doc.get("network_id");
        assertThat(networkId).containsExactly("net-only");
    }

    // ── offers.provider — descriptor / availableAt / providerAttributes ────────

    @Test
    void assemble_offerWithProviderDescriptor_storesFullProviderStructure() throws Exception {
        JsonNode payload = buildPayloadWithOffers("""
                {
                  "id": "item-1",
                  "descriptor": {"name": "EV Charger"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "EVCharger", "@context": "https://ctx"}
                }
                """, """
                [{
                  "id": "offer-1",
                  "descriptor": {"name": "Summer Discount"},
                  "provider": {
                    "id": "prov-1",
                    "descriptor": {"name": "EcoPower Charging", "shortDesc": "Clean energy provider"},
                    "availableAt": [{"id": "loc-1", "city": "Bengaluru", "country": "IND"}],
                    "providerAttributes": {"@context": "https://ctx", "@type": "ChargingProvider"}
                  },
                  "resourceIds": ["item-1"]
                }]
                """);

        Map<String, Object> doc = assembler.assemble(payload, "EVCharger");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> offers = (List<Map<String, Object>>) doc.get("offers");
        assertThat(offers).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) offers.get(0).get("provider");
        assertThat(provider.get("id")).isEqualTo("prov-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor = (Map<String, Object>) provider.get("descriptor");
        assertThat(descriptor.get("name")).isEqualTo("EcoPower Charging");
        assertThat(descriptor.get("shortDesc")).isEqualTo("Clean energy provider");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availableAt = (List<Map<String, Object>>) provider.get("availableAt");
        assertThat(availableAt).hasSize(1);
        assertThat(availableAt.get(0).get("city")).isEqualTo("Bengaluru");

        @SuppressWarnings("unchecked")
        Map<String, Object> providerAttributes = (Map<String, Object>) provider.get("providerAttributes");
        assertThat(providerAttributes.get("@type")).isEqualTo("ChargingProvider");
    }

    @Test
    void assemble_offerProviderDescriptorName_includedInTextBlob() throws Exception {
        JsonNode payload = buildPayloadWithOffers("""
                {
                  "id": "item-1",
                  "descriptor": {"name": "EV Charger"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "EVCharger", "@context": "https://ctx"}
                }
                """, """
                [{
                  "id": "offer-1",
                  "provider": {
                    "id": "prov-1",
                    "descriptor": {"name": "EcoPower Charging"}
                  },
                  "resourceIds": ["item-1"]
                }]
                """);

        Map<String, Object> doc = assembler.assemble(payload, "EVCharger");

        String blob = (String) doc.get("full_text_blob");
        assertThat(blob).contains("EcoPower Charging");
    }

    // ── full_text_blob includes constraints and policies text ─────────────────

    @Test
    void assemble_v21ItemWithConstraintsAndPolicies_textBlobIncludesTerms() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-text-blob",
                  "descriptor": {"name": "Slot Booking"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "BookingService", "@context": "https://ctx"},
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
