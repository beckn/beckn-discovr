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
        putIfPresent(contextNode, BecknFields.BAP_ID,         MdcField.BAP_ID);
        putIfPresent(contextNode, BecknFields.BAP_URI,        MdcField.BAP_URI);
        putIfPresent(contextNode, BecknFields.BPP_ID,         MdcField.BPP_ID);
        putIfPresent(contextNode, BecknFields.BPP_URI,        MdcField.BPP_URI);
        putIfPresent(contextNode, BecknFields.NETWORK_ID,     MdcField.NETWORK_ID);
        putIfPresent(contextNode, BecknFields.ACTION,         MdcField.ACTION);
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
