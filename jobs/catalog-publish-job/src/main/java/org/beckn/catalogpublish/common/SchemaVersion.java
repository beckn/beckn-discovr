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

}
