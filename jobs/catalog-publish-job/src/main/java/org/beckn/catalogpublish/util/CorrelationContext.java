package org.beckn.catalogpublish.util;

import org.beckn.catalogpublish.dto.CatalogContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MDC population and clear for trace context (messageId, bppId, transactionId,
 * correlationId).
 * MDC is populated once from the parsed context so no double-parse occurs.
 */
@Component
public class CorrelationContext {

    private static final String MDC_MESSAGE_ID = "messageId";
    private static final String MDC_BPP_ID = "bppId";
    private static final String MDC_TRANSACTION_ID = "transactionId";
    private static final String MDC_CORRELATION_ID = "correlationId";

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
                ? ctx.contextNode().path("transaction_id").asText("unknown")
                : "unknown";
        MDC.put(MDC_MESSAGE_ID, mid != null ? mid : "unknown");
        MDC.put(MDC_BPP_ID, ctx.bppId() != null ? ctx.bppId() : "unknown");
        MDC.put(MDC_TRANSACTION_ID, txnId);
        MDC.put(MDC_CORRELATION_ID, mid != null ? mid : UUID.randomUUID().toString());
    }

    /**
     * Sets minimal fallback MDC values before parsing begins.
     * Real values are populated via {@link #populate(CatalogContext, String)} after
     * parse.
     */
    public void populateFallback() {
        MDC.put(MDC_MESSAGE_ID, "unknown");
        MDC.put(MDC_BPP_ID, "unknown");
        MDC.put(MDC_TRANSACTION_ID, "unknown");
        MDC.put(MDC_CORRELATION_ID, UUID.randomUUID().toString());
    }

    public void clear() {
        MDC.clear();
    }
}
