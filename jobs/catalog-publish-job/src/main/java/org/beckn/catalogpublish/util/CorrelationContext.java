package org.beckn.catalogpublish.util;

import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.logging.MdcField;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MDC population and clear for trace context (messageId, bppId, transactionId, networkId).
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
            String bppId = ctx.contextNode().path("bppId").asText(null);
            if (bppId != null && !bppId.isBlank()) {
                MDC.put(MdcField.BPP_ID, bppId);
            } else {
                MDC.remove(MdcField.BPP_ID);
            }
            String catalogId = ctx.contextNode().path("catalogId").asText(null);
            if (catalogId != null && !catalogId.isBlank()) {
                MDC.put(MdcField.CATALOG_ID, catalogId);
            } else {
                MDC.remove(MdcField.CATALOG_ID);
            }
            String bapId = ctx.contextNode().path("bapId").asText(null);
            if (bapId != null && !bapId.isBlank()) {
                MDC.put(MdcField.BAP_ID, bapId);
            } else {
                MDC.remove(MdcField.BAP_ID);
            }
        } else {
            MDC.remove(MdcField.BPP_ID);
            MDC.remove(MdcField.CATALOG_ID);
            MDC.remove(MdcField.BAP_ID);
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
        MDC.remove(MdcField.BPP_ID);
        MDC.remove(MdcField.CATALOG_ID);
        MDC.remove(MdcField.BAP_ID);
    }

    public void clear() {
        MDC.clear();
    }
}
