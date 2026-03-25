package org.beckn.catalogpublish.util;

import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.logging.MdcField;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MDC population and clear for trace context (messageId, bppId, transactionId,
 * correlationId, networkId).
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
                ? ctx.contextNode().path("transactionId").asText(
                        ctx.contextNode().path("transaction_id").asText("unknown"))
                : "unknown";
        MDC.put(MdcField.MESSAGE_ID, mid != null ? mid : "unknown");
        MDC.put(MdcField.BPP_ID, ctx.bppId() != null ? ctx.bppId() : "unknown");
        MDC.put(MdcField.BPP_URI, ctx.bppUri() != null ? ctx.bppUri() : "unknown");
        MDC.put(MdcField.TRANSACTION_ID, txnId);
        MDC.put(MdcField.CORRELATION_ID, mid != null ? mid : UUID.randomUUID().toString());
        if (ctx.networkIds() != null && ctx.networkIds().length > 0) {
            MDC.put(MdcField.NETWORK_ID, ctx.networkIds()[0]);
        }
    }

    /**
     * Sets minimal fallback MDC values before parsing begins.
     * Real values are populated via {@link #populate(CatalogContext, String)} after
     * parse.
     */
    public void populateFallback() {
        MDC.put(MdcField.MESSAGE_ID, "unknown");
        MDC.put(MdcField.BPP_ID, "unknown");
        MDC.put(MdcField.BPP_URI, "unknown");
        MDC.put(MdcField.TRANSACTION_ID, "unknown");
        MDC.put(MdcField.CORRELATION_ID, UUID.randomUUID().toString());
    }

    public void clear() {
        MDC.clear();
    }
}
