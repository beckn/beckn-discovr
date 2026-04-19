package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.common.BecknFields;

/**
 * Shared helpers for denormalized catalog payloads.
 * Payload shape: {@code { "catalogs": [ { "resources": [ itemNode ] } ] }}.
 */
public final class DenormalizedPayloadUtils {

    private DenormalizedPayloadUtils() {
    }

    /**
     * Returns the first resource node from a denormalized payload root.
     * Uses field name {@code catalogs[0].resources[0]}.
     *
     * @param root denormalized payload root (has "catalogs" array)
     * @return first resource node, or null if missing or empty
     */
    public static JsonNode getFirstResourceNode(JsonNode root) {
        if (root == null || root.isMissingNode())
            return null;
        JsonNode catalogs = root.path(BecknFields.CATALOGS);
        if (!catalogs.isArray() || catalogs.isEmpty())
            return null;
        JsonNode cat = catalogs.get(0);
        JsonNode resources = cat.path(BecknFields.RESOURCES);
        if (resources.isMissingNode() || !resources.isArray() || resources.isEmpty())
            return null;
        return resources.get(0);
    }
}
