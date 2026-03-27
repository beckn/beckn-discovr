package org.beckn.catalogpublish.common;

/**
 * Beckn Item schema version — used internally to track which wire format an item
 * was ingested in. Never serialized to JSON or returned in any API response.
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
     * Detects the schema version from the {@code @type} value of an item node.
     * <ul>
     *   <li>{@code "beckn:Item"} → V2_0 (legacy: retained for backward-compatible reads of persisted v2.0 data)</li>
     *   <li>{@code "Item"} → V2_1</li>
     *   <li>null / anything else → V2_1 (default: all new publishes are v2.1)</li>
     * </ul>
     */
    public static SchemaVersion fromTypeValue(String typeValue) {
        if ("Item".equals(typeValue)) return V2_1;
        return V2_1;
    }
}
