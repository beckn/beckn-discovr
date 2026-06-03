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
     * Database column names
     */
    public static class ColumnNames {
        public static final String ID = "id";
        public static final String CATALOG_ID = "catalog_id";
        public static final String RESOURCE_PAYLOAD = "resource_payload";
        public static final String CATALOG_PAYLOAD = "catalog_payload";
        public static final String PAYLOAD = "payload"; // Legacy column name
    }

    /**
     * JSON field names — Beckn Protocol v2.0 (no beckn: prefix)
     */
    public static class JsonFields {
        public static final String TYPE = "@type";
        public static final String CATALOGS = "catalogs";
        public static final String BECKN_RESOURCES = "resources";
        public static final String BECKN_ID = "id";
        public static final String BECKN_DESCRIPTOR = "descriptor";
        public static final String BECKN_PROVIDER = "provider";
        public static final String BECKN_PROVIDER_ID = "providerId";
        public static final String BECKN_VALIDITY = "validity";
        public static final String BECKN_BPP_ID = "bppId";
        public static final String BECKN_BPP_URI = "bppUri";
        /** v2.1 catalog-level addOns field. */
        public static final String ADD_ONS = "addOns";
    }

    private DiscoveryConstants() {
        // Constants class - prevent instantiation
    }
}
