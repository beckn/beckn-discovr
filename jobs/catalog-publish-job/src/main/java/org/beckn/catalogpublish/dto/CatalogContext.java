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
 * by the Catalg API layer from auth). Represents the org-level identity (first segment
 * of Beckn keyId). Used for {@code subscriber_id} grouping/listing. Defaults to
 * {@code "anonymous"} when auth is disabled.</p>
 *
 * <p>{@code recordId} is the specific key holder (second segment of Beckn keyId).
 * Used for {@code created_by}/{@code updated_by} ownership tracking. May be null
 * when no recordId was provided (auth disabled without keyId header).</p>
 */
public record CatalogContext(
        List<String> networkIds,
        String subscriberId,
        String recordId,
        JsonNode contextNode) {

    public CatalogContext {
        networkIds = networkIds != null ? List.copyOf(networkIds) : List.of();
        subscriberId = (subscriberId != null && !subscriberId.isBlank()) ? subscriberId : "anonymous";
        // recordId may be null — null signals "no ownership tracking" (auth disabled)
    }
}
