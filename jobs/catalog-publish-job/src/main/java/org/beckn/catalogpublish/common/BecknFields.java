package org.beckn.catalogpublish.common;

/**
 * Beckn Protocol v2.0 field name constants.
 * Single source of truth for all JSON field names used across the job.
 */
public final class BecknFields {
    private BecknFields() {}

    // ── Top-level envelope keys ──────────────────────────────────────────────
    public static final String CONTEXT         = "context";
    public static final String MESSAGE         = "message";

    // ── Context fields (Beckn Protocol v2.0 camelCase) ───────────────────────
    public static final String DOMAIN          = "domain";
    public static final String ACTION          = "action";
    public static final String VERSION         = "version";
    public static final String TRANSACTION_ID  = "transactionId";
    public static final String MESSAGE_ID      = "messageId";
    public static final String BAP_ID          = "bapId";
    public static final String BAP_URI         = "bapUri";
    public static final String BPP_ID          = "bppId";
    public static final String BPP_URI         = "bppUri";
    public static final String NETWORK_ID      = "networkId";
    public static final String SCHEMA_CONTEXT  = "schemaContext";
    public static final String TTL             = "ttl";
    public static final String TIMESTAMP       = "timestamp";
    public static final String COUNTRY         = "country";
    public static final String CITY            = "city";

    // ── AckResponse / status fields ──────────────────────────────────────────
    public static final String STATUS          = "status";
    public static final String ERROR           = "error";
    public static final String ERROR_CODE      = "errorCode";
    public static final String ERROR_MESSAGE   = "errorMessage";

    // ── JSON-LD fields ───────────────────────────────────────────────────────
    public static final String JSON_LD_TYPE    = "@type";
    public static final String JSON_LD_CONTEXT = "@context";

    // ── Catalog / Item fields ─────────────────────────────────────────────────
    public static final String ID              = "id";
    public static final String ITEMS           = "items";
    public static final String OFFERS          = "offers";
    public static final String DESCRIPTOR      = "descriptor";
    public static final String PROVIDER        = "provider";
    public static final String ITEM_ATTRIBUTES = "itemAttributes";
    public static final String NAME            = "name";
    public static final String SHORT_DESC      = "shortDesc";
    public static final String LONG_DESC       = "longDesc";
    public static final String IMAGES          = "images";
    public static final String PROVIDER_ID     = "providerId";
    public static final String CATALOG_ID      = "catalogId";

    // ── v2.0 Resource fields (alias for Item with resourceAttributes) ─────────
    public static final String RESOURCES           = "resources";
    public static final String RESOURCE_ATTRIBUTES = "resourceAttributes";
    /** v2.0 Offer field: IDs of resources this offer applies to (replaces {@code items} in offers). */
    public static final String RESOURCE_IDS        = "resourceIds";

    // ── v2.1 Item fields ─────────────────────────────────────────────────────
    public static final String CONSTRAINTS     = "constraints";
    public static final String POLICIES        = "policies";

    // ── Publish-specific fields ──────────────────────────────────────────────
    public static final String CATALOG         = "catalog";
    public static final String CATALOGS        = "catalogs";
    public static final String PRICE           = "price";
    public static final String CURRENCY        = "currency";
    public static final String VALUE           = "value";

    // ── Action values (v2.0 slash notation) ─────────────────────────────────
    public static final String ACTION_CATALOG_PUBLISH    = "catalog/publish";
    public static final String ACTION_ON_CATALOG_PUBLISH = "catalog/on_publish";

    // ── Context V1.0 field names (snake_case — legacy Beckn v1.x) ────────────
    public static final String BAP_ID_V1         = "bap_id";
    public static final String BAP_URI_V1        = "bap_uri";
    public static final String BPP_ID_V1         = "bpp_id";
    public static final String BPP_URI_V1        = "bpp_uri";
    public static final String MESSAGE_ID_V1     = "message_id";
    public static final String TRANSACTION_ID_V1 = "transaction_id";
    public static final String DOMAIN_V1         = "domain";
    public static final String LOCATION_V1       = "location";

    // ── Context V2.0 new fields ───────────────────────────────────────────────
    public static final String TRY              = "try";
    public static final String LINEAGE          = "lineage";

    // ── Catalog new fields (v2.1 schema update) ──────────────────────────────
    public static final String ADD_ONS           = "addOns";
    public static final String ADD_ON_ATTRIBUTES = "addOnAttributes";
    public static final String ADD_ON_IDS        = "addOnIds";
    public static final String CONSIDERATION_IDS = "considerationIds";
    public static final String FULFILLMENT_IDS   = "fulfillmentIds";
    public static final String AVAILABLE_TO      = "availableTo";
    public static final String VISIBLE_TO        = "visibleTo";
}
