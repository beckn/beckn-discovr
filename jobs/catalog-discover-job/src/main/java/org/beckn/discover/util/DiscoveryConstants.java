package org.beckn.discover.util;

/**
 * Discovery Service Constants
 * 
 * Centralizes magic strings and constants used across the discovery service.
 * This improves maintainability and reduces the risk of typos.
 * 
 * @author Discovery Service V2 Team
 * @version 2.0.0
 */
public class DiscoveryConstants {

    /**
     * Default catalog attribute to extract from catalog.payload
     */
    public static final String DEFAULT_OFFER_ATTRIBUTE = "offers";

    /**
     * Beckn Item type identifier (v2.0 prefixed form)
     */
    public static final String BECKN_ITEM_TYPE = "beckn:Item";

    /**
     * Item type identifier (v2.1 unprefixed form)
     */
    public static final String ITEM_TYPE = "Item";

    /**
     * Beckn Catalog type identifier
     */
    public static final String BECKN_CATALOG_TYPE = "beckn:Catalog";

    /**
     * Beckn Offer type identifier (from schema)
     */
    public static final String BECKN_OFFER_TYPE = "beckn:Offer";

    /**
     * Default catalog context URL
     */
    public static final String DEFAULT_CATALOG_CONTEXT = "https://becknprotocol.io/schemas/core/v1/Catalog/schema-context.jsonld";

    /**
     * Database column names
     */
    public static class ColumnNames {
        public static final String ID = "id";
        public static final String CATALOG_ID = "catalog_id";
        public static final String ITEM_PAYLOAD = "item_payload";
        public static final String CATALOG_PAYLOAD = "catalog_payload";
        public static final String PAYLOAD = "payload"; // Legacy column name
    }

    /**
     * JSON field names — Beckn Protocol v2.0 (no beckn: prefix)
     */
    public static class JsonFields {
        public static final String TYPE = "@type";
        public static final String CONTEXT = "@context";
        public static final String CATALOGS = "catalogs";
        public static final String BECKN_ITEMS = "items";
        /** Old Catalog v2.0 format uses beckn: prefixed field names (e.g. stored before ParseStep normalization). */
        public static final String BECKN_ITEMS_V20 = "beckn:items";
        /** v2.1 resource-based catalogs use "resources" instead of "items". */
        public static final String BECKN_RESOURCES = "resources";
        public static final String BECKN_ID = "id";
        public static final String BECKN_DESCRIPTOR = "descriptor";
        public static final String BECKN_PROVIDER_ID = "providerId";
        public static final String BECKN_BPP_ID = "bppId";
        public static final String BECKN_BPP_URI = "bppUri";
        public static final String BECKN_VALIDITY = "validity";
        /** v2.1 catalog-level addOns field. */
        public static final String ADD_ONS = "addOns";
    }

    private DiscoveryConstants() {
        // Constants class - prevent instantiation
    }
}
