package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Extracted from the Kafka message context node; immutable, thread-safe.
 *
 * <p>{@code contextNode} is the original, unmodified context {@link JsonNode} from
 * the inbound Kafka message. It is carried through to the outbound event so the
 * full context (messageId, transactionId, networkId, etc.) is
 * forwarded as-is — no fields are lost.</p>
 */
public record CatalogContext(
        List<String> networkIds,
        JsonNode contextNode) {

    public CatalogContext {
        networkIds = networkIds != null ? List.copyOf(networkIds) : List.of();
    }
}
