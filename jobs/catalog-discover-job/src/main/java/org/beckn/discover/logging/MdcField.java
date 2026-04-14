package org.beckn.discover.logging;

/**
 * MDC key constants for structured logging across the catalog-discover-job.
 *
 * All keys declared here must match the {@code includeMdcKeyName} entries in
 * {@code logback-spring.xml} so that every key is promoted to a top-level JSON
 * field in the Logstash output.
 */
public final class MdcField {

    private MdcField() {}

    public static final String TRANSACTION_ID  = "transactionId";
    public static final String MESSAGE_ID      = "messageId";
    public static final String BAP_ID          = "bapId";
    public static final String BAP_URI         = "bapUri";
    public static final String BPP_ID          = "bppId";
    public static final String BPP_URI         = "bppUri";
    public static final String NETWORK_ID      = "networkId";
    public static final String ACTION          = "action";
    public static final String VERSION         = "version";
    public static final String TAGS            = "tags";
}
