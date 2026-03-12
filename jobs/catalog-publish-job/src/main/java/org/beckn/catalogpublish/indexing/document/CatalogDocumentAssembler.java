package org.beckn.catalogpublish.indexing.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.geometry.GeoShapeExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class CatalogDocumentAssembler {

    private final ObjectMapper objectMapper;
    private final GeoShapeExtractor geoShapeExtractor;

    public CatalogDocumentAssembler(ObjectMapper objectMapper, GeoShapeExtractor geoShapeExtractor) {
        this.objectMapper = objectMapper;
        this.geoShapeExtractor = geoShapeExtractor;
    }

    /** Called from ElasticIndexStep — Item carries bppId/bppUri directly. */
    public Map<String, Object> assemble(Item item, JsonNode payloadNode, String schemaType, String networkId) {
        return build(payloadNode, schemaType, networkId, item.getId(), item.getBppId(), item.getBppUri());
    }

    /** Called from EsFailureConsumer — all fields extracted from stored payload. */
    public Map<String, Object> assemble(JsonNode payloadNode, String indexKey) {
        JsonNode catalog = payloadNode.path("catalogs").path(0);
        JsonNode itemNode = catalog.path("beckn:items").path(0);
        JsonNode netNode = itemNode.path("beckn:networkId");
        String networkId = netNode.isArray() ? netNode.path(0).asText(null) : netNode.asText(null);
        return build(payloadNode, indexKey, networkId,
                text(catalog.path("beckn:items").path(0), "beckn:id"),
                text(catalog, "beckn:bppId"),
                text(catalog, "beckn:bppUri"));
    }

    // ── Core builder ─────────────────────────────────────────────────────────

    private Map<String, Object> build(JsonNode payloadNode, String schemaType, String networkId,
            String itemId, String bppId, String bppUri) {
        JsonNode catalog = payloadNode.path("catalogs").path(0);
        JsonNode itemNode = catalog.path("beckn:items").path(0);
        JsonNode desc = itemNode.path("beckn:descriptor");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("catalog_id", text(catalog, "beckn:id"));
        doc.put("catalog_context", text(catalog, "@context"));
        doc.put("catalog_type", text(catalog, "@type"));
        doc.put("catalog_name", text(catalog.path("beckn:descriptor"), "schema:name"));
        doc.put("catalog_images", arrayToList(catalog.path("beckn:descriptor").path("schema:image")));
        doc.put("bpp_id", bppId);
        doc.put("bpp_uri", bppUri);
        doc.put("network_id", networkId);
        doc.put("schema_type", schemaType);
        doc.put("item_context", text(itemNode, "@context"));
        doc.put("item_type", text(itemNode, "@type"));
        doc.put("item_id", itemId);
        doc.put("item_name", text(desc, "schema:name"));
        doc.put("item_short_desc", text(desc, "beckn:shortDesc"));
        doc.put("item_long_desc", text(desc, "beckn:longDesc"));
        doc.put("item_category_code", text(itemNode.path("beckn:category"), "schema:codeValue"));
        doc.put("item_category_name", text(itemNode.path("beckn:category"), "schema:name"));
        doc.put("item_rateable", bool(itemNode, "beckn:rateable"));
        doc.put("item_is_active", bool(itemNode, "beckn:isActive"));
        doc.put("item_rating_value", dbl(itemNode.path("beckn:rating"), "beckn:ratingValue"));
        doc.put("item_rating_count", integer(itemNode.path("beckn:rating"), "beckn:ratingCount"));
        doc.put("item_provider_id", text(itemNode.path("beckn:provider"), "beckn:id"));
        doc.put("item_provider_name", text(itemNode.path("beckn:provider").path("beckn:descriptor"), "schema:name"));
        doc.put("indexed_at", Instant.now().toString());

        geoShapeExtractor.extractGeoShapes(payloadNode).forEach(doc::put);

        JsonNode attrs = itemNode.path("beckn:itemAttributes");
        if (!attrs.isMissingNode() && attrs.isObject())
            doc.put("item_attributes", flattenJsonLd(attrs));

        List<Map<String, Object>> offers = buildOffers(catalog.path("beckn:offers"));
        doc.put("offers", offers);
        doc.put("full_text_blob", buildTextBlob(doc, offers, itemNode));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildOffers(JsonNode offersNode) {
        if (!offersNode.isArray())
            return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode offer : offersNode) {
            // Preserve the full original offer structure with all beckn:* field names
            Map<String, Object> o = objectMapper.convertValue(offer, Map.class);
            result.add(o);
        }
        return result;
    }

    /** Strips @context and @type — keeps only domain-specific fields. */
    private Map<String, Object> flattenJsonLd(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            result.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
        });
        return result;
    }

    private String buildTextBlob(Map<String, Object> doc, List<Map<String, Object>> offers,
                                  JsonNode itemNode) {
        List<String> parts = new ArrayList<>();

        // Core item fields
        for (String key : List.of("item_name", "item_short_desc", "item_long_desc",
                "item_category_name", "item_provider_name")) {
            if (doc.get(key) instanceof String s && !s.isBlank())
                parts.add(s);
        }

        // Task 2: text from all location objects anywhere in itemNode (any key, any depth)
        collectLocationText(itemNode, parts);

        // Task 3: all text from itemAttributes — recursive deep walk
        collectStrings(itemNode.path("beckn:itemAttributes"), parts);

        // Offer names
        offers.stream()
                .map(o -> {
                    Object desc = o.get("beckn:descriptor");
                    if (desc instanceof Map<?, ?> m)
                        return m.get("schema:name");
                    return null;
                })
                .filter(n -> n instanceof String)
                .map(Object::toString)
                .forEach(parts::add);

        return String.join(" ", parts);
    }

    /**
     * Walks the entire itemNode tree. When it finds a Location object
     * (any object containing a geo/gps/polygon field at any depth, any key name),
     * collects all non-geo string values from it (address fields, etc.).
     */
    private static void collectLocationText(JsonNode node, List<String> parts) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) {
            if (node.has("geo") || node.has("gps") || node.has("polygon")) {
                // This is a Location object — collect all non-geo text fields
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

    /**
     * Recursively collects all non-blank string leaf values from a JsonNode tree.
     * Skips JSON-LD metadata keys (@context, @type) and URL strings.
     */
    private static void collectStrings(JsonNode node, List<String> parts) {
        if (node == null || node.isMissingNode()) return;
        if (node.isTextual()) {
            String val = node.asText();
            if (!val.isBlank() && !val.startsWith("http") && !val.startsWith("@"))
                parts.add(val);
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (!e.getKey().startsWith("@"))
                    collectStrings(e.getValue(), parts);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectStrings(child, parts));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String text(JsonNode n, String f) {
        return n.path(f).asText(null);
    }

    private boolean bool(JsonNode n, String f) {
        return n.path(f).asBoolean(false);
    }

    private double dbl(JsonNode n, String f) {
        return n.path(f).asDouble(0.0);
    }

    private int integer(JsonNode n, String f) {
        return n.path(f).asInt(0);
    }

    private List<String> arrayToList(JsonNode n) {
        if (!n.isArray())
            return List.of();
        return StreamSupport.stream(n.spliterator(), false).map(JsonNode::asText).toList();
    }
}
