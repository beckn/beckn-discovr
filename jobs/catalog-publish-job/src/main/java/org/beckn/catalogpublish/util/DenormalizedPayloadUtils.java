package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared helpers for denormalized catalog payloads.
 * Payload shape: {@code { "catalogs": [ { "beckn:items": [ itemNode ] } ] }}.
 */
public final class DenormalizedPayloadUtils {

    private DenormalizedPayloadUtils() {
    }

    /**
     * Returns the first item node from a denormalized payload root.
     * Tries {@code catalogs[0].beckn:items[0]}, then {@code catalogs[0].items[0]}.
     *
     * @param root denormalized payload root (has "catalogs" array)
     * @return first item node, or null if missing or empty
     */
    public static JsonNode getFirstItemNode(JsonNode root) {
        if (root == null || root.isMissingNode())
            return null;
        JsonNode catalogs = root.path("catalogs");
        if (!catalogs.isArray() || catalogs.isEmpty())
            return null;
        JsonNode cat = catalogs.get(0);
        JsonNode items = cat.path("beckn:items");
        if (items.isMissingNode())
            items = cat.path("items");
        if (items.isMissingNode() || !items.isArray() || items.isEmpty())
            return null;
        return items.get(0);
    }
}
