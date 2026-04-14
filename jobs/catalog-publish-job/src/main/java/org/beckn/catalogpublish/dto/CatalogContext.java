package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Extracted from the Kafka message context node; immutable, thread-safe.
 *
 * <p>{@code contextNode} is the original, unmodified context {@link JsonNode} from
 * the inbound Kafka message. It is carried through to the outbound event so the
 * full context (messageId, transactionId, networkId, bppId, bppUri, etc.) is
 * forwarded as-is — no fields are lost.</p>
 *
 * <p>{@code subscriberId} is extracted from {@code context.subscriberId} (injected
 * by the Catalg API layer from auth). Used for {@code created_by}/{@code updated_by}
 * ownership tracking. Defaults to {@code "anonymous"} when auth is disabled.</p>
 */
public record CatalogContext(
        List<String> networkIds,
        String subscriberId,
        JsonNode contextNode) {

    public CatalogContext {
        networkIds = networkIds != null ? List.copyOf(networkIds) : List.of();
        subscriberId = (subscriberId != null && !subscriberId.isBlank()) ? subscriberId : "anonymous";
    }
}
