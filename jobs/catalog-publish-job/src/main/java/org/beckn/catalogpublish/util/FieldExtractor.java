package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.exception.FieldExtractionException;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

public final class FieldExtractor {
    private FieldExtractor() {
    }

    public static String requireString(JsonNode node, String fieldName) {
        return extractString(node, fieldName)
                .filter(s -> !s.isBlank())
                .orElseThrow(
                        () -> new FieldExtractionException("Required field '" + fieldName + "' is missing or empty"));
    }

    public static Optional<String> extractString(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode())
            return Optional.empty();
        String key = resolveKey(node, fieldName);
        if (key == null)
            return Optional.empty();
        JsonNode val = node.get(key);
        // val is guaranteed non-null (resolveKey confirmed node.has(key)); isNull()
        // guards JSON null.
        if (val == null || val.isNull())
            return Optional.empty();
        return Optional.of(val.asText());
    }

    public static JsonNode requireNode(JsonNode root, String fieldName) {
        String key = resolveKey(root, fieldName);
        if (key == null)
            throw new FieldExtractionException("Required node '" + fieldName + "' is missing");
        return root.get(key); // non-null: resolveKey confirmed node.has(key)
    }

    /**
     * Extracts {@code network_id} values from a Beckn {@code context} node.
     * The field may be a JSON string or a JSON array of strings.
     */
    public static String[] extractNetworkIds(JsonNode contextNode) {
        if (contextNode == null || contextNode.isMissingNode())
            return new String[0];
        return extractNetworkArray(contextNode.path("network_id"));
    }

    /**
     * Extracts {@code beckn:networkId} values from a Beckn item node.
     * Used by the catalog router to determine the leaf network of an item.
     *
     * <p>
     * Handles both prefixed ({@code beckn:networkId}) and unprefixed
     * ({@code networkId}) field names, and both string and array values.
     */
    public static String[] extractItemNetworkIds(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return new String[0];
        JsonNode field = itemNode.path("beckn:networkId");
        if (field.isMissingNode())
            field = itemNode.path("networkId");
        return extractNetworkArray(field);
    }

    // Shared logic for array/string network-ID fields — avoids duplication
    // between extractNetworkIds (context) and extractItemNetworkIds (item level).
    private static String[] extractNetworkArray(JsonNode node) {
        if (node.isMissingNode() || node.isNull())
            return new String[0];
        if (node.isArray())
            return StreamSupport.stream(node.spliterator(), false)
                    .map(JsonNode::asText)
                    .filter(s -> !s.isBlank())
                    .toArray(String[]::new);
        if (node.isTextual()) {
            String v = node.asText();
            return v.isBlank() ? new String[0] : new String[] { v };
        }
        return new String[0];
    }

    public static JsonNode extractOffersOrEmpty(JsonNode catalogNode) {
        if (catalogNode == null)
            return null;
        JsonNode n = catalogNode.path("beckn:offers");
        if (n.isMissingNode())
            n = catalogNode.path("offers");
        return n.isMissingNode() ? null : n;
    }

    /**
     * Extracts {@code @type} from {@code beckn:itemAttributes} by iterating all
     * items
     * in the catalog and returning the first non-blank value found.
     *
     * <p>
     * Spec path: {@code catalogs[].beckn:items[].beckn:itemAttributes.@type}
     * (e.g. {@code "ChargingService"}).
     *
     * <p>
     * All items in a well-formed catalog share the same schema type, so the first
     * hit is sufficient. Returns {@code "unknown"} when no item carries the field.
     */
    public static String extractSchemaTypeFromItems(JsonNode catalogNode) {
        if (catalogNode == null || catalogNode.isMissingNode())
            return "unknown";
        for (JsonNode itemNode : iterableItems(catalogNode)) {
            JsonNode attrs = itemNode.path("beckn:itemAttributes");
            if (attrs.isMissingNode())
                attrs = itemNode.path("itemAttributes");
            if (attrs.isMissingNode() || !attrs.isObject())
                continue;
            JsonNode typeNode = attrs.path("@type");
            if (!typeNode.isMissingNode() && typeNode.isTextual()) {
                String val = typeNode.asText();
                if (!val.isBlank())
                    return val;
            }
        }
        return "unknown";
    }

    /**
     * @deprecated Use {@link #extractSchemaTypeFromItems(JsonNode)} for catalog
     *             nodes.
     *             This method is retained for item-level use inside
     *             {@link #extractItemType(JsonNode)}.
     */
    @Deprecated
    public static String extractSchemaType(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return "unknown";
        JsonNode n = itemNode.path("schema_type");
        if (n.isMissingNode())
            n = itemNode.path("beckn:schemaType");
        return (n.isMissingNode() || !n.isTextual()) ? "unknown" : n.asText("unknown");
    }

    public static Iterable<JsonNode> iterableItems(JsonNode catalogNode) {
        return iterableField(catalogNode, "beckn:items", "items");
    }

    private static Iterable<JsonNode> iterableField(JsonNode node, String becknKey, String plainKey) {
        if (node == null)
            return List.of();
        JsonNode field = node.path(becknKey);
        if (field.isMissingNode())
            field = node.path(plainKey);
        if (field.isMissingNode() || !field.isArray())
            return List.of();
        final JsonNode arr = field;
        return arr::elements;
    }

    /**
     * Item display name from descriptor (schema:name, name, or beckn:shortDesc).
     */
    public static String extractItemName(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode desc = resolveNode(itemNode, "descriptor");
        if (desc.isMissingNode() || !desc.isObject())
            return null;
        return extractString(desc, "schema:name")
                .or(() -> extractString(desc, "name"))
                .or(() -> extractString(desc, "beckn:shortDesc"))
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /**
     * Item type/category from category (schema:codeValue or schema:name) or
     * schemaType.
     */
    public static String extractItemType(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode cat = resolveNode(itemNode, "category");
        if (!cat.isMissingNode() && cat.isObject()) {
            String v = extractString(cat, "schema:codeValue")
                    .or(() -> extractString(cat, "schema:name"))
                    .or(() -> extractString(cat, "codeValue"))
                    .filter(s -> !s.isBlank())
                    .orElse(null);
            if (v != null)
                return v;
        }
        return extractSchemaType(itemNode);
    }

    /**
     * Item attributes @context from beckn:itemAttributes (string or first element
     * of array).
     * Falls back to null when itemAttributes is missing or malformed.
     */
    public static String extractItemAttributesContextUrl(JsonNode itemNode) {
        JsonNode attrs = itemAttributesNode(itemNode);
        return attrs == null ? null : extractContextUrl(attrs);
    }

    /**
     * Item attributes @type from beckn:itemAttributes (e.g. "ChargingService").
     * Returns null when missing or blank; callers should apply their own fallback.
     */
    public static String extractItemAttributesType(JsonNode itemNode) {
        JsonNode attrs = itemAttributesNode(itemNode);
        if (attrs == null)
            return null;
        JsonNode typeNode = attrs.path("@type");
        if (!typeNode.isTextual())
            return null;
        String v = typeNode.asText();
        return v.isBlank() ? null : v;
    }

    /** Provider ID from item's beckn:provider (object with id, or plain string). */
    public static String extractItemProviderId(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode prov = resolveNode(itemNode, "provider");
        if (prov.isMissingNode() || prov.isNull())
            return null;
        if (prov.isTextual())
            return prov.asText(null);
        return extractString(prov, "beckn:id").or(() -> extractString(prov, "id")).orElse(null);
    }

    /** @context URL from catalog or item (string or first element of array). */
    public static String extractContextUrl(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        JsonNode ctx = node.get("@context");
        if (ctx == null || ctx.isMissingNode() || ctx.isNull())
            return null;
        if (ctx.isTextual())
            return ctx.asText(null);
        if (ctx.isArray() && !ctx.isEmpty())
            return ctx.get(0).asText(null);
        return null;
    }

    /**
     * Tries fieldName directly, then "beckn:fieldName", then any
     * "prefix:fieldName".
     */
    private static String resolveKey(JsonNode node, String fieldName) {
        if (node.has(fieldName))
            return fieldName;
        if (node.has("beckn:" + fieldName))
            return "beckn:" + fieldName;
        var it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (k.endsWith(":" + fieldName))
                return k;
        }
        return null;
    }

    /**
     * Resolves a child node by checking "beckn:plainKey" first, then "plainKey".
     * Returns MissingNode (never null) when neither key is present — safe for
     * chaining.
     */
    private static JsonNode resolveNode(JsonNode parent, String plainKey) {
        JsonNode n = parent.path("beckn:" + plainKey);
        return n.isMissingNode() ? parent.path(plainKey) : n;
    }

    private static JsonNode itemAttributesNode(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode attrs = resolveNode(itemNode, "itemAttributes");
        return (attrs.isMissingNode() || !attrs.isObject()) ? null : attrs;
    }
}
