package org.beckn.discover.common;

/**
 * Beckn Protocol v2.0 field name constants.
 * Single source of truth for all JSON field names used across the job.
 * Use in @JsonProperty annotations, JsonNode path traversal, and test assertions.
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

    // ── AckResponse fields ───────────────────────────────────────────────────
    public static final String STATUS          = "status";
    public static final String ERROR           = "error";
    public static final String ERROR_CODE      = "errorCode";
    public static final String ERROR_MESSAGE   = "errorMessage";

    // ── JSON-LD fields ────────────────────────────────────────────────────────
    public static final String AT_CONTEXT      = "@context";
    public static final String AT_TYPE         = "@type";

    // ── Catalog / Item fields (v2.0 — no beckn: prefix) ─────────────────────
    public static final String ID              = "id";
    public static final String TYPE            = "type";
    /** @deprecated Retained only for excluding legacy field from copies. All new writes use RESOURCES. */
    @Deprecated
    public static final String ITEMS           = "items";
    public static final String OFFERS          = "offers";
    public static final String DESCRIPTOR      = "descriptor";
    public static final String PROVIDER        = "provider";
    public static final String NAME            = "name";
    public static final String SHORT_DESC      = "shortDesc";
    public static final String LONG_DESC       = "longDesc";
    public static final String IMAGES          = "images";
    public static final String PROVIDER_ID     = "providerId";

    // ── v2.0 Resource fields (alias for Item with resourceAttributes) ─────────
    public static final String RESOURCES           = "resources";
    public static final String RESOURCE_ATTRIBUTES = "resourceAttributes";

    // ── Catalog validity field ────────────────────────────────────────────────
    public static final String VALIDITY        = "validity";

    // ── Discover message fields ───────────────────────────────────────────────
    public static final String INTENT          = "intent";
    public static final String TEXT_SEARCH     = "textSearch";
    public static final String FILTERS         = "filters";
    public static final String SPATIAL         = "spatial";
    public static final String CATALOGS        = "catalogs";

    // ── Action values (v2.0 slash notation) ─────────────────────────────────
    public static final String ACTION_CATALOG_PUBLISH    = "catalog/publish";
    public static final String ACTION_ON_CATALOG_PUBLISH = "catalog/on_publish";

}
