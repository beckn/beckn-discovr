package org.beckn.discover.common;

/**
 * Beckn Item schema version — used internally to track which wire format an item
 * was stored in. Never serialized to JSON or returned in any API response.
 */
public enum SchemaVersion {

    V2_0("2.0"),
    V2_1("2.1");

    private final String value;

    SchemaVersion(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Detects the schema version from the {@code @type} value of a resource node.
     * <ul>
     *   <li>{@code "beckn:Resource"} → V2_1</li>
     *   <li>null / anything else → V2_1 (default: all new publishes are v2.1)</li>
     * </ul>
     */
    public static SchemaVersion fromTypeValue(String typeValue) {
        return V2_1;
    }
}
