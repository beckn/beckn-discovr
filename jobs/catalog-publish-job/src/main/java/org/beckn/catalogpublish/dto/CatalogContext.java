package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracted from the Kafka message context node; immutable, thread-safe.
 *
 * <p>
 * {@code contextNode} is the original, unmodified context {@link JsonNode} from
 * the inbound Kafka message. It is carried through to the outbound event so the
 * full context (messageId, transactionId, networkId, etc.) is forwarded as-is —
 * no fields are lost.
 */
public record CatalogContext(String bppId, String bppUri, String[] networkIds, JsonNode contextNode) {

    /** Defensive copy of mutable array to guarantee thread safety. */
    public CatalogContext {
        networkIds = networkIds != null ? networkIds.clone() : new String[0];
    }

    /** Returns a defensive copy — callers cannot mutate the internal array. */
    @Override
    public String[] networkIds() {
        return networkIds.clone();
    }
}
