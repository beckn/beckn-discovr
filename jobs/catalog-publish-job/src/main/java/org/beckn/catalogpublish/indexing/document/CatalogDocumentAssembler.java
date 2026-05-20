package org.beckn.catalogpublish.indexing.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.geometry.GeoShapeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class CatalogDocumentAssembler {

    private static final Logger log = LoggerFactory.getLogger(CatalogDocumentAssembler.class);
    private static final int DEFAULT_MAX_TEXT_BLOB_BYTES = 8192;

    private final ObjectMapper objectMapper;
    private final GeoShapeExtractor geoShapeExtractor;
    private final int maxTextBlobBytes;

    public CatalogDocumentAssembler(ObjectMapper objectMapper,
                                    GeoShapeExtractor geoShapeExtractor,
                                    AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.geoShapeExtractor = geoShapeExtractor;
        var indexing = appProperties.catalog().indexing();
        this.maxTextBlobBytes = (indexing != null) ? indexing.maxTextBlobBytes() : DEFAULT_MAX_TEXT_BLOB_BYTES;
    }

    /** Called from ElasticIndexStep — builds the ES document from the Item and its payload. */
    public Map<String, Object> assemble(Item item, JsonNode payloadNode, String schemaType, List<String> networkIds) {
        return build(payloadNode, schemaType, networkIds, item.getId());
    }

    /** Called from EsFailureConsumer — all fields extracted from stored payload. */
    public Map<String, Object> assemble(JsonNode payloadNode, String indexKey) {
        JsonNode catalog = payloadNode.path(BecknFields.CATALOGS).path(0);
        JsonNode resourceNode = catalog.path(BecknFields.RESOURCES).path(0);
        JsonNode netNode = payloadNode.path(BecknFields.CONTEXT).path(BecknFields.NETWORK_ID);
        if (netNode.isMissingNode()) netNode = catalog.path(BecknFields.NETWORK_ID);
        List<String> networkIds;
        if (netNode.isArray()) {
            networkIds = new ArrayList<>();
            netNode.forEach(n -> {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) networkIds.add(v);
            });
        } else {
            String single = netNode.asText(null);
            networkIds = (single != null && !single.isBlank()) ? List.of(single) : List.of();
        }
        return build(payloadNode, indexKey, networkIds, text(resourceNode, BecknFields.ID));
    }

    // ── Core builder ─────────────────────────────────────────────────────────

    private Map<String, Object> build(JsonNode payloadNode, String schemaType, List<String> networkIds,
            String resourceId) {
        JsonNode catalog = payloadNode.path(BecknFields.CATALOGS).path(0);
        JsonNode resourceNode = catalog.path(BecknFields.RESOURCES).path(0);
        JsonNode desc = resourceNode.path(BecknFields.DESCRIPTOR);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("catalog_id", text(catalog, BecknFields.ID));
        doc.put("catalog_context", text(catalog, BecknFields.JSON_LD_CONTEXT));
        doc.put("catalog_type", text(catalog, BecknFields.JSON_LD_TYPE));
        JsonNode catalogDesc = catalog.path(BecknFields.DESCRIPTOR);
        doc.put("catalog_name", text(catalogDesc, BecknFields.NAME));
        doc.put("catalog_short_desc", text(catalogDesc, BecknFields.SHORT_DESC));
        doc.put("catalog_long_desc", text(catalogDesc, BecknFields.LONG_DESC));
        doc.put("catalog_descriptor_thumbnail_image", text(catalogDesc, "thumbnailImage"));
        doc.put("catalog_descriptor_docs", convertToList(catalogDesc.path("docs")));
        doc.put("catalog_descriptor_media_file", convertToList(catalogDesc.path("mediaFile")));
        doc.put("catalog_provider_id", text(catalog.path(BecknFields.PROVIDER), BecknFields.ID));
        doc.put("catalog_provider_name",
                text(catalog.path(BecknFields.PROVIDER).path(BecknFields.DESCRIPTOR), BecknFields.NAME));
        putIfPresent(doc, "catalog_is_active", boolOrNull(catalog, "isActive"));
        doc.put("network_id", networkIds);
        JsonNode validityNode = catalog.path(BecknFields.VALIDITY);
        if (!validityNode.isMissingNode() && validityNode.isObject()) {
            doc.put("catalog_validity", objectMapper.convertValue(validityNode, Map.class));
        }
        doc.put("schema_type", schemaType);
        doc.put("resource_context", text(resourceNode, BecknFields.JSON_LD_CONTEXT));
        doc.put("resource_type", text(resourceNode, BecknFields.JSON_LD_TYPE));
        doc.put("resource_id", resourceId);
        doc.put("resource_name", text(desc, BecknFields.NAME));
        doc.put("resource_short_desc", text(desc, BecknFields.SHORT_DESC));
        doc.put("resource_long_desc", text(desc, BecknFields.LONG_DESC));
        doc.put("resource_category_code", text(resourceNode.path("category"), "codeValue"));
        doc.put("resource_category_name", text(resourceNode.path("category"), BecknFields.NAME));
        putIfPresent(doc, "resource_rateable", boolOrNull(resourceNode, "rateable"));
        putIfPresent(doc, "resource_is_active", boolOrNull(resourceNode, "isActive"));
        JsonNode ratingNode = resourceNode.path("rating");
        putIfPresent(doc, "resource_rating_value", dblOrNull(ratingNode, "ratingValue"));
        putIfPresent(doc, "resource_rating_count", intOrNull(ratingNode, "ratingCount"));
        doc.put("resource_provider_id", text(resourceNode.path(BecknFields.PROVIDER), BecknFields.ID));
        doc.put("resource_provider_name",
                text(resourceNode.path(BecknFields.PROVIDER).path(BecknFields.DESCRIPTOR), BecknFields.NAME));
        doc.put("resource_descriptor_thumbnail_image", text(desc, "thumbnailImage"));
        doc.put("resource_descriptor_docs", convertToList(desc.path("docs")));
        doc.put("resource_descriptor_media_file", convertToList(desc.path("mediaFile")));
        doc.put("resource_rating_review_text", text(resourceNode.path("rating"), "reviewText"));
        doc.put("indexed_at", Instant.now().toString());

        geoShapeExtractor.extractGeoShapes(payloadNode).forEach(doc::put);

        JsonNode attrs = resourceNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        if (!attrs.isMissingNode() && attrs.isObject()) {
            doc.put("resource_attributes", flattenJsonLd(attrs));
            doc.put("resource_attributes_type", text(attrs, BecknFields.JSON_LD_TYPE));
            doc.put("resource_attributes_context", text(attrs, BecknFields.JSON_LD_CONTEXT));
        }

        JsonNode constraintsNode = resourceNode.path(BecknFields.CONSTRAINTS);
        if (!constraintsNode.isMissingNode() && constraintsNode.isArray()) {
            doc.put("constraints", objectMapper.convertValue(constraintsNode, List.class));
        }
        JsonNode policiesNode = resourceNode.path(BecknFields.POLICIES);
        if (!policiesNode.isMissingNode() && policiesNode.isArray()) {
            doc.put("policies", objectMapper.convertValue(policiesNode, List.class));
        }

        JsonNode offersNode = catalog.path(BecknFields.OFFERS);
        List<Map<String, Object>> offers = buildOffers(offersNode);
        doc.put("offers", offers);
        doc.put("full_text_blob", buildTextBlob(doc, offersNode, resourceNode));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildOffers(JsonNode offersNode) {
        if (!offersNode.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode offer : offersNode) {
            Map<String, Object> o = objectMapper.convertValue(offer, Map.class);
            result.add(o);
        }
        return result;
    }

    private Map<String, Object> flattenJsonLd(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            result.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
        });
        return result;
    }

    private String buildTextBlob(Map<String, Object> doc, JsonNode offersNode, JsonNode resourceNode) {
        Set<String> parts = new LinkedHashSet<>();
        for (String key : List.of("resource_name", "resource_short_desc", "resource_long_desc",
                "resource_category_name", "resource_provider_name")) {
            if (doc.get(key) instanceof String s && !s.isBlank())
                parts.add(s);
        }
        collectLocationText(resourceNode, parts);
        JsonNode attrsForText = resourceNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        collectStrings(attrsForText, parts);
        collectStrings(resourceNode.path(BecknFields.CONSTRAINTS), parts);
        collectStrings(resourceNode.path(BecknFields.POLICIES), parts);
        collectStrings(resourceNode.path(BecknFields.DESCRIPTOR).path("docs"), parts);
        collectStrings(resourceNode.path(BecknFields.DESCRIPTOR).path("mediaFile"), parts);
        collectStrings(offersNode, parts);
        String blob = String.join(" ", parts);
        TruncationResult result = truncateAtWordBoundary(blob, maxTextBlobBytes);
        if (result.wasTruncated()) {
            log.warn("event={} catalogId={} resourceId={} originalBytes={} truncatedBytes={}",
                    LogEvent.FULL_TEXT_BLOB_TRUNCATED,
                    doc.get("catalog_id"),
                    doc.get("resource_id"),
                    result.originalBytes(), result.truncatedBytes());
        }
        return result.text();
    }

    private record TruncationResult(String text, int originalBytes, int truncatedBytes) {
        boolean wasTruncated() { return truncatedBytes < originalBytes; }
    }

    /**
     * Truncates {@code text} to at most {@code maxBytes} bytes (UTF-8), breaking
     * only at a word boundary (space) so no token is split mid-word.
     */
    private static TruncationResult truncateAtWordBoundary(String text, int maxBytes) {
        if (text == null) return new TruncationResult(null, 0, 0);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int originalBytes = bytes.length;
        if (originalBytes <= maxBytes) {
            return new TruncationResult(text, originalBytes, originalBytes);
        }
        // Walk back from maxBytes until we hit a space or start of string
        int cut = maxBytes;
        while (cut > 0 && bytes[cut] != ' ') {
            cut--;
        }
        if (cut == 0) {
            // No space found — hard cut at maxBytes (avoids empty string for long tokens)
            cut = maxBytes;
        }
        String result = new String(bytes, 0, cut, StandardCharsets.UTF_8).stripTrailing();
        int truncatedBytes = result.getBytes(StandardCharsets.UTF_8).length;
        return new TruncationResult(result, originalBytes, truncatedBytes);
    }

    private static void collectLocationText(JsonNode node, Collection<String> parts) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) {
            if (node.has("geo") || node.has("gps") || node.has("polygon")) {
                node.fields().forEachRemaining(e -> {
                    if (!e.getKey().equals("geo") && !e.getKey().equals("gps")
                            && !e.getKey().equals("polygon") && !e.getKey().startsWith("@"))
                        collectStrings(e.getValue(), parts);
                });
            } else {
                node.fields().forEachRemaining(e -> collectLocationText(e.getValue(), parts));
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectLocationText(child, parts));
        }
    }

    private static void collectStrings(JsonNode node, Collection<String> parts) {
        if (node == null || node.isMissingNode()) return;
        if (node.isTextual()) {
            String val = node.asText();
            if (!val.isBlank() && !val.startsWith("http://") && !val.startsWith("https://"))
                parts.add(val);
        } else if (node.isNumber()) {
            parts.add(node.asText());
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (!key.startsWith("@") && !key.equals("geo") && !key.equals("gps") && !key.equals("polygon")) {
                    if (e.getValue().isBoolean() && e.getValue().booleanValue()) parts.add(key);
                    collectStrings(e.getValue(), parts);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectStrings(child, parts));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> convertToList(JsonNode n) {
        if (n == null || n.isMissingNode() || !n.isArray()) return null;
        return objectMapper.convertValue(n, List.class);
    }

    private String text(JsonNode n, String f) { return n.path(f).asText(null); }

    private Boolean boolOrNull(JsonNode n, String f) {
        JsonNode field = n.path(f);
        return field.isBoolean() ? field.booleanValue() : null;
    }

    private Double dblOrNull(JsonNode n, String f) {
        JsonNode field = n.path(f);
        return field.isNumber() ? field.doubleValue() : null;
    }

    private Integer intOrNull(JsonNode n, String f) {
        JsonNode field = n.path(f);
        return field.isNumber() ? field.intValue() : null;
    }

    private static void putIfPresent(Map<String, Object> doc, String key, Object value) {
        if (value != null) doc.put(key, value);
    }
}
