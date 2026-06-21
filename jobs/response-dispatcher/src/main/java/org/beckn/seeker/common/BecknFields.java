package org.beckn.seeker.common;

/**
 * Beckn Protocol v2.0 field name constants.
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

    // ── AckResponse / on_discover fields ────────────────────────────────────
    public static final String STATUS          = "status";
    public static final String ERROR           = "error";
    public static final String CODE            = "code";

    // ── Catalog / Item fields (v2.0 — no beckn: prefix) ─────────────────────
    public static final String ID              = "id";
    public static final String RESOURCES       = "resources";
    public static final String OFFERS          = "offers";
    public static final String DESCRIPTOR      = "descriptor";
    public static final String PROVIDER        = "provider";
    public static final String NAME            = "name";
    public static final String CATALOGS        = "catalogs";

    // ── v2.1 Resource fields ─────────────────────────────────────────────────
    public static final String RESOURCE_ATTRIBUTES = "resourceAttributes";

    // ── on_discover specific ─────────────────────────────────────────────────
    public static final String REQUEST_DIGEST  = "requestDigest";

    // ── Action values (v2.0 slash notation) ─────────────────────────────────
    public static final String ACTION_CATALOG_PUBLISH    = "catalog/publish";
    public static final String ACTION_ON_CATALOG_PUBLISH = "catalog/on_publish";

    // ── Auth / ownership (MDC keys) ──────────────────────────────────────────
    /** MDC key: org-level identity from auth header keyId first segment. */
    public static final String AUTH_SUBSCRIBER_ID = "auth.subscriberId";
    /** MDC key: key-level identity from auth header keyId second segment. */
    public static final String AUTH_RECORD_ID     = "auth.recordId";

    // ── Response envelope (dispatcher / discover transport) ─────────────────
    /** Wrapper field added by catalog-discover-job around every outbound response. */
    public static final String META             = "meta";
    /** Kafka-durable subscriber identity inside {@code meta}. */
    public static final String SUBSCRIBER_ID    = "subscriber_id";
    /** Kafka-durable record (key) identity inside {@code meta}. */
    public static final String RECORD_ID        = "record_id";
    /** The actual Beckn response payload inside the envelope. */
    public static final String PAYLOAD          = "payload";
}
