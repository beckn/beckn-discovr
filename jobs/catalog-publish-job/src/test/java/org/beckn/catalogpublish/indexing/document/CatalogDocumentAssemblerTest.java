package org.beckn.catalogpublish.indexing.document;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.service.geometry.GeoShapeExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        var indexing = new AppProperties.Indexing(8192);
        var catalog = mock(AppProperties.Catalog.class);
        when(catalog.indexing()).thenReturn(indexing);
        var props = mock(AppProperties.class);
        when(props.catalog()).thenReturn(catalog);
        assembler = new CatalogDocumentAssembler(OM, geoShapeExtractor, props);
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
    void assemble_payloadOverload_schemaVersionNotInDocument() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-v20",
                  "descriptor": {"name": "Item V20"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "ServiceItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ServiceItem");

        // schema_version has been removed from the ES document model (v2.1 schema redesign)
        assertThat(doc).doesNotContainKey("schema_version");
        // bpp_id and bpp_uri must not appear in ES documents
        assertThat(doc).doesNotContainKey("bpp_id");
        assertThat(doc).doesNotContainKey("bpp_uri");
    }

    @Test
    void assemble_itemViaItemOverload_setsCatalogIdNotBppId() throws Exception {
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-v21", "{}", new String[0], "cat-1",
                "SmartMeter", null, new String[]{"net-1"});

        JsonNode payload = buildPayload("""
                {
                  "id": "item-v21",
                  "descriptor": {"name": "v2.1 Item"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "SmartMeter", "@context": "https://example.org"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(item, payload, "SmartMeter", List.of("net-1"));

        assertThat(doc.get("catalog_id")).isEqualTo("cat-1");
        assertThat(doc.containsKey("bpp_id")).isFalse();
        assertThat(doc.containsKey("bpp_uri")).isFalse();
        assertThat(doc.containsKey("schema_version")).isFalse();
        assertThat(doc.get("schema_type")).isEqualTo("SmartMeter");
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

        assertThat(doc.get("catalog_provider_id")).isEqualTo("prov-catalog");
        assertThat(doc.get("catalog_provider_name")).isEqualTo("Catalog Provider");
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
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-multi-net", "{}", new String[0], "cat-1",
                "GenericItem", null, new String[]{"net-a", "net-b"});

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
        org.beckn.catalogpublish.model.Item item = org.beckn.catalogpublish.model.Item.from(
                "item-single-net", "{}", new String[0], "cat-1",
                "GenericItem", null, new String[]{"net-only"});

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

    // ── full_text_blob: numeric values ────────────────────────────────────────

    @Test
    void assemble_numericResourceAttributes_includedInTextBlob() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-ev-numeric",
                  "descriptor": {"name": "EV Fast Charger"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {
                    "@context": "https://example.org/ev.jsonld",
                    "@type": "ChargingService",
                    "powerKw": 150,
                    "pricePerKwh": 12.5
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        String blob = (String) doc.get("full_text_blob");
        assertThat(blob).contains("150");
        assertThat(blob).contains("12.5");
    }

    // ── full_text_blob: boolean key names ─────────────────────────────────────

    @Test
    void assemble_booleanTrueResourceAttributes_keyNameIncludedInTextBlob() throws Exception {
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-organic",
                  "descriptor": {"name": "Organic Produce"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {
                    "@context": "https://example.org/food.jsonld",
                    "@type": "GroceryItem",
                    "organic": true,
                    "frozen": false
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GroceryItem");

        String blob = (String) doc.get("full_text_blob");
        assertThat(blob).contains("organic");
        assertThat(blob).doesNotContain("frozen");
    }

    // ── full_text_blob: deduplication ─────────────────────────────────────────

    @Test
    void assemble_duplicateTextInBlob_deduplicatedInOutput() throws Exception {
        // resource_name "EV Charger" also appears in resourceAttributes.label — should appear once
        JsonNode payload = buildPayload("""
                {
                  "@type": "beckn:Resource",
                  "id": "item-dedup",
                  "descriptor": {
                    "name": "EV Charger",
                    "shortDesc": "EV Charger"
                  },
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {
                    "@context": "https://example.org/ev.jsonld",
                    "@type": "ChargingService",
                    "label": "EV Charger"
                  }
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "ChargingService");

        String blob = (String) doc.get("full_text_blob");
        // Split on space and count occurrences of "EV" to verify no duplicates from dedup
        String[] tokens = blob.split("\\s+");
        long count = java.util.Arrays.stream(tokens).filter("EV Charger"::equals).count();
        // The full phrase won't occur as a single token, but we can count how many times
        // "EV" appears — with LinkedHashSet dedup it should appear exactly once
        long evCount = java.util.Arrays.stream(tokens).filter("EV"::equals).count();
        assertThat(evCount).isEqualTo(1L);
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

    // ── M4: full_text_blob truncation at 8KB word boundary ───────────────────

    @Test
    void assemble_textBlobUnder8KB_notTruncated() throws Exception {
        // short description stays well under 8192 bytes
        JsonNode payload = buildPayload("""
                {
                  "id": "item-short",
                  "descriptor": {"name": "Short Item", "shortDesc": "Brief description."},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """);

        Map<String, Object> doc = assembler.assemble(payload, "GenericItem");

        String blob = (String) doc.get("full_text_blob");
        assertThat(blob.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(8192);
        assertThat(blob).contains("Short Item");
        assertThat(blob).contains("Brief description.");
    }

    @Test
    void assemble_textBlobOver8KB_truncatedAtWordBoundary() throws Exception {
        // Build a long description that exceeds 8192 bytes when assembled
        String word = "superlongword";
        String longDesc = (word + " ").repeat(700); // ~9800 bytes

        JsonNode payload = buildPayload(String.format("""
                {
                  "id": "item-long",
                  "descriptor": {"name": "Long Item", "longDesc": "%s"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """, longDesc.trim()));

        // Use assembler with a small max (512 bytes) to test truncation without needing 8KB of data
        var indexing512 = new AppProperties.Indexing(512);
        var catalog512 = mock(AppProperties.Catalog.class);
        when(catalog512.indexing()).thenReturn(indexing512);
        var props512 = mock(AppProperties.class);
        when(props512.catalog()).thenReturn(catalog512);
        when(geoShapeExtractor.extractGeoShapes(any())).thenReturn(Map.of());
        var assembler512 = new CatalogDocumentAssembler(OM, geoShapeExtractor, props512);

        Map<String, Object> doc = assembler512.assemble(payload, "GenericItem");

        String blob = (String) doc.get("full_text_blob");
        byte[] blobBytes = blob.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Must be at or below the 512-byte cap
        assertThat(blobBytes.length).isLessThanOrEqualTo(512);
        // Must not end mid-word (every token is the full "superlongword")
        assertThat(blob.endsWith(word) || blob.endsWith("Long Item") || blob.isEmpty()
                || !blob.endsWith(" ")).isTrue();
        // Blob must start correctly (name comes first)
        assertThat(blob).startsWith("Long Item");
    }

    @Test
    void assemble_textBlobTruncatedDoesNotSplitMidWord() throws Exception {
        // 10-char words separated by spaces — truncation must land on a space
        String word = "abcdefghij"; // 10 chars
        // Build exactly enough words so the joined string exceeds 100 bytes
        // "abcdefghij abcdefghij ..." — each word+space = 11 bytes
        // 10 words = 110 bytes (last space stripped = 109), 9 words = 99
        String manyWords = (word + " ").repeat(20).trim();

        JsonNode payload = buildPayload(String.format("""
                {
                  "id": "item-word-boundary",
                  "descriptor": {"name": "%s"},
                  "provider": {"id": "prov-1"},
                  "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                }
                """, manyWords));

        // Cap at 50 bytes — forces truncation in the middle of the word list
        var indexing50 = new AppProperties.Indexing(50);
        var catalog50 = mock(AppProperties.Catalog.class);
        when(catalog50.indexing()).thenReturn(indexing50);
        var props50 = mock(AppProperties.class);
        when(props50.catalog()).thenReturn(catalog50);
        when(geoShapeExtractor.extractGeoShapes(any())).thenReturn(Map.of());
        var assembler50 = new CatalogDocumentAssembler(OM, geoShapeExtractor, props50);

        Map<String, Object> doc = assembler50.assemble(payload, "GenericItem");

        String blob = (String) doc.get("full_text_blob");
        // Must not end with a partial word
        if (!blob.isEmpty()) {
            // Every token should be exactly the 10-char word
            for (String token : blob.split("\\s+")) {
                assertThat(token).hasSize(word.length())
                        .withFailMessage("Blob was truncated mid-word: '%s'", blob);
            }
        }
        assertThat(blob.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(50);
    }

    // ── full_text_blob truncation WARN log ────────────────────────────────────

    @Test
    void assemble_textBlobTruncated_emitsWarnLogWithExpectedFields() throws Exception {
        // Use a tiny cap (64 bytes) so even a short description triggers truncation.
        var indexing64 = new AppProperties.Indexing(64);
        var catalog64 = mock(AppProperties.Catalog.class);
        when(catalog64.indexing()).thenReturn(indexing64);
        var props64 = mock(AppProperties.class);
        when(props64.catalog()).thenReturn(catalog64);
        when(geoShapeExtractor.extractGeoShapes(any())).thenReturn(Map.of());
        var assembler64 = new CatalogDocumentAssembler(OM, geoShapeExtractor, props64);

        // Attach a ListAppender to the assembler's logger before running.
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(CatalogDocumentAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        assemblerLogger.addAppender(appender);

        try {
            // Build a resource whose blob will definitely exceed 64 bytes.
            JsonNode payload = buildPayload("""
                    {
                      "id": "res-truncate-log",
                      "descriptor": {
                        "name": "A fairly long resource name that alone exceeds sixty four bytes of UTF-8",
                        "shortDesc": "And additional short description text pushes it further over the limit"
                      },
                      "provider": {"id": "prov-1"},
                      "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                    }
                    """);

            Map<String, Object> doc = assembler64.assemble(payload, "GenericItem");

            // Verify the blob is within the cap.
            String blob = (String) doc.get("full_text_blob");
            assertThat(blob.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);

            // Verify the WARN log was emitted exactly once with the correct event and fields.
            // catalogId must appear inline (MDC is never populated for this field — see CorrelationContext).
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .anySatisfy(e -> {
                        String msg = e.getFormattedMessage();
                        assertThat(msg).contains(LogEvent.FULL_TEXT_BLOB_TRUNCATED);
                        assertThat(msg).contains("catalogId=cat-1");
                        assertThat(msg).contains("resourceId=res-truncate-log");
                        assertThat(msg).containsPattern("originalBytes=\\d+");
                        assertThat(msg).containsPattern("truncatedBytes=\\d+");
                    });

            // Verify originalBytes > truncatedBytes in the log message (truncation actually happened).
            ILoggingEvent warnEvent = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains(LogEvent.FULL_TEXT_BLOB_TRUNCATED))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected WARN log not found"));
            Object[] args = warnEvent.getArgumentArray();
            // args: [event-const, catalogId, resourceId, originalBytes, truncatedBytes]
            int originalBytes = (Integer) args[3];
            int truncatedBytes = (Integer) args[4];
            assertThat(originalBytes).isGreaterThan(64);
            assertThat(truncatedBytes).isLessThanOrEqualTo(64);
            assertThat(truncatedBytes).isLessThan(originalBytes);
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }

    @Test
    void assemble_textBlobUnderCap_noWarnLogFired() throws Exception {
        // Attach a ListAppender before running with the default 8192-byte cap.
        Logger assemblerLogger = (Logger) LoggerFactory.getLogger(CatalogDocumentAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        assemblerLogger.addAppender(appender);

        try {
            // Very short blob — well under 8192 bytes.
            JsonNode payload = buildPayload("""
                    {
                      "id": "res-short",
                      "descriptor": {"name": "Short Item", "shortDesc": "Brief."},
                      "provider": {"id": "prov-1"},
                      "resourceAttributes": {"@type": "GenericItem", "@context": "https://ctx"}
                    }
                    """);

            assembler.assemble(payload, "GenericItem");

            // No WARN log with the truncation event should have been emitted.
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains(LogEvent.FULL_TEXT_BLOB_TRUNCATED))
                    .isEmpty();
        } finally {
            assemblerLogger.detachAppender(appender);
        }
    }
}
