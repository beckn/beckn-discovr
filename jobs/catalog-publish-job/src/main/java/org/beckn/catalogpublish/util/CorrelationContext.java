package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.logging.MdcField;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MDC population and clear for trace context (messageId, transactionId, networkId, catalogId).
 * MDC is populated once from the parsed context so no double-parse occurs.
 */
@Component
public class CorrelationContext {

    public CorrelationContext() {
    }

    /**
     * Populates MDC trace context from an already-parsed {@link CatalogContext} and
     * message ID.
     * Avoids re-parsing the raw JSON string (ParseStep has already done so).
     * Overwrites any fallback MDC values set earlier.
     */
    public void populate(CatalogContext ctx, String messageId) {
        String mid = (messageId != null && !messageId.isBlank()) ? messageId : null;
        String txnId = ctx.contextNode() != null
                ? ctx.contextNode().path("transactionId").asText(null)
                : null;
        if (mid != null) MDC.put(MdcField.MESSAGE_ID, mid); else MDC.remove(MdcField.MESSAGE_ID);
        if (txnId != null) MDC.put(MdcField.TRANSACTION_ID, txnId); else MDC.remove(MdcField.TRANSACTION_ID);
        List<String> networkIds = ctx.networkIds();
        if (networkIds != null && !networkIds.isEmpty()) {
            MDC.put(MdcField.NETWORK_ID, networkIds.get(0));
        }
        if (ctx.contextNode() != null) {
            String catalogId = ctx.contextNode().path("catalogId").asText(null);
            if (catalogId != null && !catalogId.isBlank()) {
                MDC.put(MdcField.CATALOG_ID, catalogId);
            } else {
                MDC.remove(MdcField.CATALOG_ID);
            }
            MDC.remove(MdcField.AUTH_SUBSCRIBER_ID);
            MDC.remove(MdcField.AUTH_RECORD_ID);
            // publishTimestamp — epoch millis from distribution envelope context
            String publishTimestamp = ctx.contextNode().path("publishTimestamp").asText(null);
            if (publishTimestamp != null && !publishTimestamp.isBlank()) {
                MDC.put(MdcField.PUBLISH_TIMESTAMP, publishTimestamp);
            } else {
                MDC.remove(MdcField.PUBLISH_TIMESTAMP);
            }
        } else {
            MDC.remove(MdcField.CATALOG_ID);
            MDC.remove(MdcField.AUTH_SUBSCRIBER_ID);
            MDC.remove(MdcField.AUTH_RECORD_ID);
            MDC.remove(MdcField.PUBLISH_TIMESTAMP);
        }
    }

    /**
     * Sets minimal fallback MDC values before parsing begins.
     * Real values are populated via {@link #populate(CatalogContext, String)} after
     * parse.
     */
    public void populateFallback() {
        MDC.remove(MdcField.MESSAGE_ID);
        MDC.remove(MdcField.TRANSACTION_ID);
        MDC.remove(MdcField.CATALOG_ID);
        MDC.remove(MdcField.AUTH_SUBSCRIBER_ID);
        MDC.remove(MdcField.AUTH_RECORD_ID);
        MDC.remove(MdcField.PUBLISH_TIMESTAMP);
    }

    /**
     * Sets the {@code tags} MDC field from a raw Kafka header byte array.
     * No-op when {@code tagsHeader} is null or blank.
     *
     * @param tagsHeader raw bytes from the {@code tags} Kafka record header
     */
    public void setTags(byte[] tagsHeader) {
        if (tagsHeader != null && tagsHeader.length > 0) {
            var tags = new String(tagsHeader, java.nio.charset.StandardCharsets.UTF_8);
            if (!tags.isBlank()) {
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
    public void setTagsFromHttp(String tagsHeader) {
        if (tagsHeader != null && !tagsHeader.isBlank()) {
            MDC.put(MdcField.TAGS, tagsHeader);
        }
    }

    /**
     * Populates MDC with {@code transactionId} and {@code messageId} from a raw Beckn
     * {@code context} node, for synchronous HTTP entry points (e.g. {@code /catalog/push})
     * that log a milestone before the async publish pipeline runs. Only these two correlation
     * IDs are set — {@code networkId}/{@code catalogId} are populated later by the publish
     * consumer via {@link #populate(CatalogContext, String)}. No-op for absent/blank values.
     *
     * <p>Callers MUST {@link #clear()} in a finally so IDs do not leak across pooled request
     * threads.
     */
    public void populateEntryIds(JsonNode contextNode) {
        if (contextNode == null || !contextNode.isObject()) {
            return;
        }
        String txnId = contextNode.path("transactionId").asText(null);
        String msgId = contextNode.path("messageId").asText(null);
        if (txnId != null && !txnId.isBlank()) {
            MDC.put(MdcField.TRANSACTION_ID, txnId);
        }
        if (msgId != null && !msgId.isBlank()) {
            MDC.put(MdcField.MESSAGE_ID, msgId);
        }
    }

    public void clear() {
        MDC.clear();
    }
}
