package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracted from the Kafka message context node; immutable, thread-safe.
 *
 * <p>
 * {@code contextNode} is the original, unmodified context {@link JsonNode} from
 * the
 * inbound Kafka message. It is carried through to the outbound event so the
 * full context
 * (message_id, transaction_id, network_id, etc.) is forwarded as-is — no fields
 * are lost.
 */
public record CatalogContext(String bppId, String bppUri, String[] networkIds, JsonNode contextNode) {
}
