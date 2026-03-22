package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.common.SchemaVersion;

import java.util.Iterator;
import java.util.Map;

/**
 * Pure static utility for normalizing Beckn Item v2.0 ({@code beckn:} prefixed) and
 * v2.1 (unprefixed) payloads to a canonical unprefixed form for internal processing.
 *
 * <p><strong>CRITICAL:</strong> {@code @type} and {@code @context} keys are JSON-LD markers —
 * they are NEVER stripped or renamed. Only the {@code beckn:} prefix on regular field names
 * is removed.</p>
 *
 * <p>The normalizer accepts an {@link ObjectMapper} parameter so callers can pass Spring Boot's
 * auto-configured bean — no {@code new ObjectMapper()} is ever constructed here.</p>
 */
public final class BecknFieldNormalizer {

    private static final String BECKN_PREFIX = "beckn:";

    private BecknFieldNormalizer() {}

    // ── Version detection ─────────────────────────────────────────────────────

    /**
     * Detects the schema version by reading the {@code @type} field of the item node.
     * Returns {@link SchemaVersion#V2_0} when the field is absent or unrecognized.
     */
    public static SchemaVersion detectVersion(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode()) {
            return SchemaVersion.V2_0;
        }
        JsonNode typeNode = itemNode.get(BecknFields.JSON_LD_TYPE);
        if (typeNode == null || typeNode.isNull() || !typeNode.isTextual()) {
            return SchemaVersion.V2_0;
        }
        return SchemaVersion.fromTypeValue(typeNode.asText());
    }

    // ── Normalization ─────────────────────────────────────────────────────────

    /**
     * Normalizes a single item node: recursively strips the {@code beckn:} prefix from all
     * field names. v2.1 items (already unprefixed) pass through unchanged — idempotent.
     *
     * @param itemNode the item JsonNode to normalize
     * @param om       the ObjectMapper used for deep-copying — must be Spring Boot's configured bean
     * @return a new JsonNode with all {@code beckn:} prefixes removed from field names
     */
    public static JsonNode normalizeItem(JsonNode itemNode, ObjectMapper om) {
        if (itemNode == null || itemNode.isMissingNode()) {
            return itemNode;
        }
        if (!itemNode.isObject()) {
            return itemNode;
        }
        return normalizeObject((ObjectNode) itemNode, om);
    }

    /**
     * Normalizes a full catalog node: strips {@code beckn:} prefix from catalog-level fields
     * and from every item and offer within the catalog.
     *
     * @param catalogNode the catalog JsonNode
     * @param om          the ObjectMapper — must be Spring Boot's configured bean
     * @return a new JsonNode with all {@code beckn:} prefixes removed from field names
     */
    public static JsonNode normalizeCatalog(JsonNode catalogNode, ObjectMapper om) {
        if (catalogNode == null || catalogNode.isMissingNode()) {
            return catalogNode;
        }
        if (!catalogNode.isObject()) {
            return catalogNode;
        }
        return normalizeObject((ObjectNode) catalogNode, om);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Recursively normalizes an ObjectNode: for each field, the key has its {@code beckn:}
     * prefix stripped (if present), and the value is recursively normalized.
     *
     * <p>{@code @context} and {@code @type} keys are preserved exactly — they are JSON-LD
     * markers and must never be renamed.</p>
     */
    private static ObjectNode normalizeObject(ObjectNode node, ObjectMapper om) {
        ObjectNode result = om.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String originalKey = entry.getKey();
            JsonNode value = entry.getValue();

            // JSON-LD marker keys: preserve exactly — never strip
            String normalizedKey = originalKey.startsWith("@") ? originalKey : stripBecknPrefix(originalKey);

            result.set(normalizedKey, normalizeValue(value, om));
        }
        return result;
    }

    private static JsonNode normalizeValue(JsonNode value, ObjectMapper om) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return value;
        }
        if (value.isObject()) {
            return normalizeObject((ObjectNode) value, om);
        }
        if (value.isArray()) {
            ArrayNode result = om.createArrayNode();
            for (JsonNode element : value) {
                result.add(normalizeValue(element, om));
            }
            return result;
        }
        // Scalar (text, number, boolean) — return as-is
        return value;
    }

    /**
     * Removes the {@code "beckn:"} prefix from {@code key} if present; otherwise returns
     * the key unchanged. Preserves all other keys including those starting with {@code @}.
     */
    private static String stripBecknPrefix(String key) {
        if (key != null && key.startsWith(BECKN_PREFIX)) {
            return key.substring(BECKN_PREFIX.length());
        }
        return key;
    }
}
