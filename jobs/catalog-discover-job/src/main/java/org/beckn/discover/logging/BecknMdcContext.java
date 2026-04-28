package org.beckn.discover.logging;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.discover.model.Context;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Static utility for populating SLF4J MDC with Beckn context fields.
 *
 * <p>Call {@link #populate(JsonNode)} immediately after parsing the request
 * JSON node, and {@link #clear()} in a {@code finally} block to ensure MDC is
 * never leaked to unrelated requests on the same thread.</p>
 *
 * <p>MDC propagation across thread boundaries: capture with
 * {@link MDC#getCopyOfContextMap()} before spawning an async task, then restore
 * with {@link MDC#setContextMap(Map)} at the start of the task's lambda.</p>
 */
public final class BecknMdcContext {

    private BecknMdcContext() {}

    /**
     * Populates MDC from a {@code context} JSON node.
     *
     * @param contextNode the {@code context} object from the Beckn request
     */
    public static void populate(JsonNode contextNode) {
        putIfPresent(contextNode, "transactionId",  MdcField.TRANSACTION_ID);
        putIfPresent(contextNode, "messageId",      MdcField.MESSAGE_ID);
        putIfPresent(contextNode, "networkId",      MdcField.NETWORK_ID);
    }

    /**
     * Populates MDC from a deserialized {@link Context} model object.
     *
     * @param context the Beckn {@link Context} POJO
     */
    public static void populate(Context context) {
        if (context == null) return;
        putIfNotBlank(MdcField.TRANSACTION_ID, context.getTransactionId());
        putIfNotBlank(MdcField.MESSAGE_ID,     context.getMessageId());
        putIfNotBlank(MdcField.NETWORK_ID,     context.getNetworkId());
    }

    /**
     * Sets auth identity fields from a parsed auth principal.
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
    private static final int MAX_TAGS_LENGTH = 256;

    public static void setTags(byte[] tagsHeader) {
        if (tagsHeader != null && tagsHeader.length > 0) {
            var raw = new String(tagsHeader, java.nio.charset.StandardCharsets.UTF_8);
            var tags = raw.replaceAll("[\\r\\n\\t]", "").strip();
            if (!tags.isBlank() && tags.length() <= MAX_TAGS_LENGTH) {
                MDC.put(MdcField.TAGS, tags);
            }
        }
    }

    /**
     * Sets the {@code tags} MDC field from an HTTP header string value.
     * No-op when {@code tagsHeader} is null or blank.
     *
     * @param tagsHeader value of the {@code X-Tags} HTTP request header
     */
    public static void setTagsFromHttp(String tagsHeader) {
        if (tagsHeader != null && !tagsHeader.isBlank()) {
            var tags = tagsHeader.replaceAll("[\\r\\n\\t]", "").strip();
            if (!tags.isBlank() && tags.length() <= MAX_TAGS_LENGTH) {
                MDC.put(MdcField.TAGS, tags);
            }
        }
    }

    /**
     * Clears all Beckn MDC keys set by this class.
     * Must be called in a {@code finally} block after each request or message.
     */
    public static void clear() {
        MDC.remove(MdcField.TRANSACTION_ID);
        MDC.remove(MdcField.MESSAGE_ID);
        MDC.remove(MdcField.NETWORK_ID);
        MDC.remove(MdcField.AUTH_SUBSCRIBER_ID);
        MDC.remove(MdcField.AUTH_RECORD_ID);
        MDC.remove(MdcField.TAGS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void putIfPresent(JsonNode node, String jsonField, String mdcKey) {
        if (node == null) return;
        JsonNode child = node.path(jsonField);
        if (!child.isMissingNode() && !child.isNull() && child.isTextual()) {
            String text = child.asText();
            if (!text.isBlank()) MDC.put(mdcKey, text);
        }
    }

    private static void putIfNotBlank(String mdcKey, String value) {
        if (value != null && !value.isBlank()) MDC.put(mdcKey, value);
    }
}
