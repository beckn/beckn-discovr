package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.common.BecknFields;
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
     * Extracts {@code networkId} values from a Beckn v2.0 {@code context} node.
     * The field may be a JSON string or a JSON array of strings.
     */
    public static String[] extractNetworkIds(JsonNode contextNode) {
        if (contextNode == null || contextNode.isMissingNode())
            return new String[0];
        return extractNetworkArray(contextNode.path(BecknFields.NETWORK_ID));
    }

    /**
     * Extracts {@code networkId} from a Beckn v2.1 resource node.
     * In v2.1, {@code networkId} is a single string (not an array).
     * Returns a single-element array if present, or empty array if absent.
     */
    public static String[] extractResourceNetworkIds(JsonNode resourceNode) {
        if (resourceNode == null || resourceNode.isMissingNode())
            return new String[0];
        JsonNode field = resourceNode.path(BecknFields.NETWORK_ID);
        return extractNetworkArray(field);
    }

    // Shared logic for array/string network-ID fields — avoids duplication
    // between extractNetworkIds (context) and extractResourceNetworkIds (resource level).
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
        JsonNode n = catalogNode.path(BecknFields.OFFERS);
        return n.isMissingNode() ? null : n;
    }

    /**
     * Extracts {@code @type} from {@code resourceAttributes} by iterating all
     * resources in the catalog and returning the first non-blank value found.
     *
     * <p>
     * Spec path: {@code catalogs[].resources[].resourceAttributes.@type}
     * (e.g. {@code "ChargingService"}).
     *
     * <p>
     * All resources in a well-formed catalog share the same schema type, so the first
     * hit is sufficient. Returns {@code null} when no resource carries the field.
     */
    public static String extractSchemaTypeFromResources(JsonNode catalogNode) {
        return extractSchemaType(catalogNode, null);
    }

    /**
     * Extracts schema type with context.schemaContext support (Gap 2 fix).
     *
     * <p>Priority order (matches beckn-catalg indexer ParseStep behaviour):
     * <ol>
     *   <li>{@code context.schemaContext} — full URL; fragment after {@code #} is used as type
     *       (e.g. {@code "https://…/context.jsonld#ChargingService"} → {@code "ChargingService"}).
     *       Used for v2.0 publishes that carry only a context-level schema pointer.</li>
     *   <li>{@code resourceAttributes.@type} from first resource — authoritative domain schema type.</li>
     * </ol>
     *
     * @param catalogNode  normalized catalog JsonNode
     * @param contextNode  the {@code context} JsonNode from the Kafka message root; may be null
     */
    public static String extractSchemaType(JsonNode catalogNode, JsonNode contextNode) {
        // 1. context.schemaContext — extract fragment after '#' as schema type
        if (contextNode != null && !contextNode.isMissingNode()) {
            JsonNode scNode = contextNode.path(BecknFields.SCHEMA_CONTEXT);
            if (scNode.isTextual()) {
                String sc = scNode.asText().trim();
                if (!sc.isBlank()) {
                    int h = sc.lastIndexOf('#');
                    // Fragment present → use as schema type; otherwise use full URL
                    return h >= 0 && h < sc.length() - 1 ? sc.substring(h + 1) : sc;
                }
            }
        }
        // 2. resourceAttributes @type from first resource
        if (catalogNode != null && !catalogNode.isMissingNode()) {
            for (JsonNode itemNode : iterableResources(catalogNode)) {
                JsonNode attrs = itemNode.path(BecknFields.RESOURCE_ATTRIBUTES);
                if (attrs.isMissingNode() || !attrs.isObject())
                    continue;
                JsonNode typeNode = attrs.path(BecknFields.JSON_LD_TYPE);
                if (typeNode.isTextual()) {
                    String val = typeNode.asText();
                    if (!val.isBlank()) return val;
                }
            }
        }
        return null;
    }

    /**
     * @deprecated Use {@link #extractSchemaTypeFromResources(JsonNode)} for catalog nodes.
     *             This method is retained for resource-level use inside {@link #extractResourceType(JsonNode)}.
     */
    @Deprecated
    public static String extractSchemaType(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode n = itemNode.path("schemaType"); // legacy field, not a BecknFields constant
        return (n.isMissingNode() || !n.isTextual()) ? null : n.asText(null);
    }

    /**
     * Returns true if the resource node represents a real published resource.
     *
     * <p>A minimal resource from an offer-only catalog has only {@code id} +
     * {@code resourceAttributes} with just {@code @type} and {@code @context} —
     * no {@code descriptor}, no domain-specific attributes. These must not create item rows.
     *
     * <p>Detection (either condition makes it real):
     * <ol>
     *   <li>{@code descriptor.name} is present and non-blank</li>
     *   <li>{@code resourceAttributes} has any field beyond {@code @type}/{@code @context}
     *       (e.g., price, weight, specs — domain fields)</li>
     * </ol>
     */
    public static boolean isRealResource(JsonNode resourceNode) {
        if (resourceNode == null || resourceNode.isMissingNode()) return false;

        // Check 1: descriptor.name present and non-blank → real resource
        JsonNode name = resourceNode.path(BecknFields.DESCRIPTOR).path(BecknFields.NAME);
        if (name.isTextual() && !name.asText().isBlank()) return true;

        // Check 2: resourceAttributes has domain fields beyond @type/@context → real resource
        JsonNode attrs = resourceNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        if (attrs.isObject()) {
            var fields = attrs.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                if (!BecknFields.JSON_LD_TYPE.equals(f) && !BecknFields.JSON_LD_CONTEXT.equals(f)) return true;
            }
        }

        return false;
    }

    public static Iterable<JsonNode> iterableResources(JsonNode catalogNode) {
        if (catalogNode == null)
            return List.of();
        JsonNode field = catalogNode.path(BecknFields.RESOURCES);
        if (field.isMissingNode() || !field.isArray())
            return List.of();
        final JsonNode arr = field;
        return arr::elements;
    }

    /**
     * Resource display name from descriptor (name or shortDesc) — v2.1 format.
     */
    public static String extractResourceName(JsonNode resourceNode) {
        if (resourceNode == null || resourceNode.isMissingNode())
            return null;
        JsonNode desc = resourceNode.path(BecknFields.DESCRIPTOR);
        if (desc.isMissingNode() || !desc.isObject())
            return null;
        return extractString(desc, BecknFields.NAME)
                .or(() -> extractString(desc, BecknFields.SHORT_DESC))
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /**
     * Resource schema type from {@code resourceAttributes.@type}.
     * Returns null when absent; callers fall back to {@link #extractSchemaTypeFromResources}.
     */
    public static String extractResourceType(JsonNode resourceNode) {
        if (resourceNode == null || resourceNode.isMissingNode())
            return null;
        JsonNode attrs = resourceNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        if (!attrs.isMissingNode() && attrs.isObject()) {
            JsonNode typeNode = attrs.path(BecknFields.JSON_LD_TYPE);
            if (typeNode.isTextual()) {
                String v = typeNode.asText();
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    /**
     * Resource attributes @context from resourceAttributes (string or first element of array).
     * Falls back to null when resourceAttributes is missing or malformed.
     */
    public static String extractResourceAttributesContextUrl(JsonNode resourceNode) {
        JsonNode attrs = itemAttributesNode(resourceNode);
        return attrs == null ? null : extractContextUrl(attrs);
    }

    /**
     * Resource attributes @type from resourceAttributes (e.g. "ChargingService").
     * Returns null when missing or blank; callers should apply their own fallback.
     */
    public static String extractResourceAttributesType(JsonNode resourceNode) {
        JsonNode attrs = itemAttributesNode(resourceNode);
        if (attrs == null)
            return null;
        JsonNode typeNode = attrs.path(BecknFields.JSON_LD_TYPE);
        if (!typeNode.isTextual())
            return null;
        String v = typeNode.asText();
        return v.isBlank() ? null : v;
    }

    /** Provider ID from resource's provider field (object with id, or plain string) — v2.1 format. */
    public static String extractResourceProviderId(JsonNode resourceNode) {
        if (resourceNode == null || resourceNode.isMissingNode())
            return null;
        JsonNode prov = resourceNode.path(BecknFields.PROVIDER);
        if (prov.isMissingNode() || prov.isNull())
            return null;
        if (prov.isTextual())
            return prov.asText(null);
        return extractString(prov, BecknFields.ID).orElse(null);
    }

    /** Context URL from a node — checks {@code @context} (JSON-LD root) or plain {@code context} (itemAttributes). */
    public static String extractContextUrl(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        JsonNode ctx = node.get(BecknFields.JSON_LD_CONTEXT);
        if (ctx == null || ctx.isMissingNode() || ctx.isNull())
            ctx = node.get("context");
        if (ctx == null || ctx.isMissingNode() || ctx.isNull())
            return null;
        if (ctx.isTextual())
            return ctx.asText(null);
        if (ctx.isArray() && !ctx.isEmpty())
            return ctx.get(0).asText(null);
        return null;
    }

    /**
     * Tries fieldName directly — v2.0 only, no beckn: prefix fallback.
     */
    private static String resolveKey(JsonNode node, String fieldName) {
        return node.has(fieldName) ? fieldName : null;
    }

    private static JsonNode itemAttributesNode(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode())
            return null;
        JsonNode attrs = itemNode.path(BecknFields.RESOURCE_ATTRIBUTES);
        return (attrs.isMissingNode() || !attrs.isObject()) ? null : attrs;
    }
}
