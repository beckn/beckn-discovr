package org.beckn.discover.logging;

/**
 * Canonical MDC key names — shared standard across ALL Beckn services.
 *
 * <p>Every MdcField.java in every job declares ALL constants so developers
 * know the full field vocabulary. A job that does not SET a field simply
 * omits it from MDC; the constant still exists here for cross-service consistency.
 */
public final class MdcField {

    private MdcField() {}

    // ── Common — ALL services ────────────────────────────────────────────────
    public static final String TRANSACTION_ID     = "transactionId";
    public static final String MESSAGE_ID         = "messageId";
    public static final String CATALOG_ID         = "catalogId";
    public static final String NETWORK_ID         = "networkId";
    public static final String BPP_ID             = "bppId";
    public static final String BAP_ID             = "bapId";
    public static final String BAP_URI            = "bapUri";
    public static final String BPP_URI            = "bppUri";
    public static final String ACTION             = "action";
    public static final String VERSION            = "version";
    /** Org-level identity from auth header keyId first segment. */
    public static final String AUTH_SUBSCRIBER_ID = "auth.subscriberId";
    /** Key-level identity from auth header keyId second segment. */
    public static final String AUTH_RECORD_ID     = "auth.recordId";
    public static final String SCHEMA_TYPE        = "schemaType";
    /** Epoch millis when the catalog was published (replaces catalogVersion). */
    public static final String PUBLISH_TIMESTAMP  = "publishTimestamp";
    public static final String SUBSCRIPTION_ID    = "subscriptionId";
    public static final String TASK_ID            = "taskId";
    public static final String TAGS               = "tags";
}
