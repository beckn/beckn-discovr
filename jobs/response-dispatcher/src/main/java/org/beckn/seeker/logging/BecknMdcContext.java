package org.beckn.seeker.logging;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.seeker.common.BecknFields;
import org.slf4j.MDC;

/**
 * Populates and clears SLF4J MDC from a Beckn v2.0 context node.
 * Call {@link #populate(JsonNode)} at the start of each Kafka message handler
 * and {@link #clear()} in the finally block.
 */
public final class BecknMdcContext {

    private BecknMdcContext() {}

    public static void populate(JsonNode contextNode) {
        putIfPresent(contextNode, BecknFields.TRANSACTION_ID, MdcField.TRANSACTION_ID);
        putIfPresent(contextNode, BecknFields.MESSAGE_ID,     MdcField.MESSAGE_ID);
        putIfPresent(contextNode, BecknFields.NETWORK_ID,     MdcField.NETWORK_ID);
        // auth identity fields — set by Catalg API from auth header keyId
        putIfPresent(contextNode, BecknFields.AUTH_SUBSCRIBER_ID, MdcField.AUTH_SUBSCRIBER_ID);
        putIfPresent(contextNode, BecknFields.AUTH_RECORD_ID,     MdcField.AUTH_RECORD_ID);
    }

    /**
     * Sets auth identity fields explicitly (e.g. when parsed from a separate auth object).
     *
     * @param subscriberId org-level identity (keyId first segment); may be null
     * @param recordId     key-level identity (keyId second segment); may be null
     */
    public static void setAuthFields(String subscriberId, String recordId) {
        if (subscriberId != null && !subscriberId.isBlank()) {
            MDC.put(MdcField.AUTH_SUBSCRIBER_ID, subscriberId);
        }
        if (recordId != null && !recordId.isBlank()) {
            MDC.put(MdcField.AUTH_RECORD_ID, recordId);
        }
    }

    /**
     * Sets the {@code tags} MDC field from a raw Kafka header byte array.
     * No-op when {@code tagsHeader} is null or blank.
     *
     * @param tagsHeader raw bytes from the {@code tags} Kafka record header
     */
    public static void setTags(byte[] tagsHeader) {
        if (tagsHeader != null && tagsHeader.length > 0) {
            var tags = new String(tagsHeader, java.nio.charset.StandardCharsets.UTF_8);
            if (!tags.isBlank()) {
                MDC.put(MdcField.TAGS, tags);
            }
        }
    }

    public static void clear() {
        MDC.clear();
    }

    private static void putIfPresent(JsonNode node, String jsonField, String mdcKey) {
        JsonNode field = node.path(jsonField);
        if (!field.isMissingNode() && !field.isNull()) {
            MDC.put(mdcKey, field.asText());
        }
    }
}
