package org.beckn.catalogpublish.indexing.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.common.BecknFields;
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
    public Map<String, Object> assemble(Item item, JsonNode payloadNode, String schemaType, List<String> networkIds) {
        return build(payloadNode, schemaType, networkIds, item.getId(), item.getBppId(), item.getBppUri(),
                item.getSchemaVersion());
    }

    /** Called from EsFailureConsumer — all fields extracted from stored payload. */
    public Map<String, Object> assemble(JsonNode payloadNode, String indexKey) {
        JsonNode catalog = payloadNode.path(BecknFields.CATALOGS).path(0);
        JsonNode itemsOrResources = catalog.path(BecknFields.ITEMS);
        if (itemsOrResources.isMissingNode() || !itemsOrResources.isArray()) {
            itemsOrResources = catalog.path(BecknFields.RESOURCES);
        }
        JsonNode itemNode = itemsOrResources.path(0);
        JsonNode netNode = itemNode.path(BecknFields.NETWORK_ID);
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
        // schema_version not available from payload alone — default to "2.0" for retry path
        return build(payloadNode, indexKey, networkIds,
                text(itemNode, BecknFields.ID),
                text(catalog, BecknFields.BPP_ID),
                text(catalog, BecknFields.BPP_URI),
                "2.0");
    }

    // ── Core builder ─────────────────────────────────────────────────────────

    private Map<String, Object> build(JsonNode payloadNode, String schemaType, List<String> networkIds,
            String itemId, String bppId, String bppUri, String schemaVersion) {
        JsonNode catalog = payloadNode.path(BecknFields.CATALOGS).path(0);
        JsonNode itemsOrResources = catalog.path(BecknFields.ITEMS);
        if (itemsOrResources.isMissingNode() || !itemsOrResources.isArray()) {
            // v2.0 resource-based catalogs use "resources" instead of "items"
            itemsOrResources = catalog.path(BecknFields.RESOURCES);
        }
        JsonNode itemNode = itemsOrResources.path(0);
        JsonNode desc = itemNode.path(BecknFields.DESCRIPTOR);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("catalog_id", text(catalog, BecknFields.ID));
        doc.put("catalog_context", text(catalog, BecknFields.JSON_LD_CONTEXT));
        doc.put("catalog_type", text(catalog, BecknFields.JSON_LD_TYPE));
        doc.put("catalog_name", text(catalog.path(BecknFields.DESCRIPTOR), BecknFields.NAME));
        doc.put("catalog_images", arrayToList(catalog.path(BecknFields.DESCRIPTOR).path(BecknFields.IMAGES)));
        doc.put("bpp_id", bppId);
        doc.put("bpp_uri", bppUri);
        doc.put("network_id", networkIds);
        doc.put("schema_type", schemaType);
        doc.put("item_context", text(itemNode, BecknFields.JSON_LD_CONTEXT));
        doc.put("item_type", text(itemNode, BecknFields.JSON_LD_TYPE));
        doc.put("item_id", itemId);
        doc.put("item_name", text(desc, BecknFields.NAME));
        doc.put("item_short_desc", text(desc, BecknFields.SHORT_DESC));
        doc.put("item_long_desc", text(desc, BecknFields.LONG_DESC));
        doc.put("item_image", arrayToList(desc.path(BecknFields.IMAGES)));
        doc.put("item_category_code", text(itemNode.path("category"), "codeValue"));
        doc.put("item_category_name", text(itemNode.path("category"), BecknFields.NAME));
        doc.put("item_rateable", bool(itemNode, "rateable"));
        doc.put("item_is_active", bool(itemNode, "isActive"));
        doc.put("item_rating_value", dbl(itemNode.path("rating"), "ratingValue"));
        doc.put("item_rating_count", integer(itemNode.path("rating"), "ratingCount"));
        doc.put("item_provider_id", text(itemNode.path(BecknFields.PROVIDER), BecknFields.ID));
        doc.put("item_provider_name", text(itemNode.path(BecknFields.PROVIDER).path(BecknFields.DESCRIPTOR), BecknFields.NAME));
        doc.put("item_descriptor_thumbnail_image", text(desc, "thumbnailImage"));
        doc.put("item_descriptor_docs", convertToList(desc.path("docs")));
        doc.put("item_descriptor_media_file", convertToList(desc.path("mediaFile")));
        doc.put("item_provider_alerts", convertToList(itemNode.path(BecknFields.PROVIDER).path("alerts")));
        doc.put("item_provider_policies", convertToList(itemNode.path(BecknFields.PROVIDER).path("policies")));
        doc.put("item_rating_review_text", text(itemNode.path("rating"), "reviewText"));
        // Internal metadata — never returned in API responses
        doc.put("schema_version", schemaVersion != null ? schemaVersion : "2.0");
        doc.put("indexed_at", Instant.now().toString());

        geoShapeExtractor.extractGeoShapes(payloadNode).forEach(doc::put);

        JsonNode attrs = itemNode.path(BecknFields.ITEM_ATTRIBUTES);
        if (attrs.isMissingNode() || !attrs.isObject()) {
            // v2.0 resource-based items use "resourceAttributes" instead of "itemAttributes"
            attrs = itemNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        }
        if (!attrs.isMissingNode() && attrs.isObject()) {
            doc.put("item_attributes", flattenJsonLd(attrs));
            // Dedicated top-level ES fields for @type and @context so they can be
            // filtered as keywords without navigating into the nested object.
            doc.put("item_attributes_type", text(attrs, BecknFields.JSON_LD_TYPE));
            doc.put("item_attributes_context", text(attrs, BecknFields.JSON_LD_CONTEXT));
        }

        // v2.1 fields: constraints and policies
        JsonNode constraintsNode = itemNode.path(BecknFields.CONSTRAINTS);
        if (!constraintsNode.isMissingNode() && constraintsNode.isArray()) {
            doc.put("constraints", objectMapper.convertValue(constraintsNode, List.class));
        }
        JsonNode policiesNode = itemNode.path(BecknFields.POLICIES);
        if (!policiesNode.isMissingNode() && policiesNode.isArray()) {
            doc.put("policies", objectMapper.convertValue(policiesNode, List.class));
        }

        JsonNode offersNode = catalog.path(BecknFields.OFFERS);
        List<Map<String, Object>> offers = buildOffers(offersNode);
        doc.put("offers", offers);
        doc.put("full_text_blob", buildTextBlob(doc, offersNode, itemNode));
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

    /** Flattens a JSON-LD node into a plain Map, preserving all fields including @context and @type. */
    private Map<String, Object> flattenJsonLd(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            result.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
        });
        return result;
    }

    private String buildTextBlob(Map<String, Object> doc, JsonNode offersNode, JsonNode itemNode) {
        List<String> parts = new ArrayList<>();

        // Core item fields
        for (String key : List.of("item_name", "item_short_desc", "item_long_desc",
                "item_category_name", "item_provider_name")) {
            if (doc.get(key) instanceof String s && !s.isBlank())
                parts.add(s);
        }

        // Text from all location objects anywhere in itemNode (any key, any depth)
        collectLocationText(itemNode, parts);

        // All text from itemAttributes/resourceAttributes — recursive deep walk
        JsonNode attrsForText = itemNode.path(BecknFields.ITEM_ATTRIBUTES);
        if (attrsForText.isMissingNode()) {
            attrsForText = itemNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        }
        collectStrings(attrsForText, parts);

        // v2.1: text from constraints and policies
        collectStrings(itemNode.path(BecknFields.CONSTRAINTS), parts);
        collectStrings(itemNode.path(BecknFields.POLICIES), parts);

        // Descriptor docs and mediaFile text
        collectStrings(itemNode.path(BecknFields.DESCRIPTOR).path("docs"), parts);
        collectStrings(itemNode.path(BecknFields.DESCRIPTOR).path("mediaFile"), parts);

        // All text fields from offers (names, descriptions, terms, eligibility, etc.)
        collectStrings(offersNode, parts);

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
                String key = e.getKey();
                if (!key.startsWith("@") && !key.equals("geo") && !key.equals("gps") && !key.equals("polygon"))
                    collectStrings(e.getValue(), parts);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectStrings(child, parts));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Object> convertToList(JsonNode n) {
        if (n == null || n.isMissingNode() || !n.isArray())
            return null;
        return objectMapper.convertValue(n, List.class);
    }

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
