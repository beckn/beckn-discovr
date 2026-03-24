package org.beckn.discover.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.discover.common.BecknFields;

import java.util.Map;

/**
 * Normalizes a Beckn Context from V1.0 (snake_case) to V2.0 (camelCase).
 *
 * <p>Applied once to the raw {@link JsonNode} at the earliest entry point
 * ({@link org.beckn.discover.controller.DiscoveryController}) so all
 * downstream raw-node access reads only V2.0 field names via
 * {@link BecknFields} constants. Idempotent — V2.0 contexts pass through
 * unchanged. V1.0-only fields ({@code domain}) are preserved as-is.
 *
 * <p>For the Jackson POJO deserialization path, V1.0 field names are handled
 * by {@code @JsonAlias} annotations on {@link org.beckn.discover.model.Context}.
 */
public final class ContextNormalizer {

    private ContextNormalizer() {}

    private static final Map<String, String> V1_TO_V2 = Map.of(
            BecknFields.BAP_ID_V1,         BecknFields.BAP_ID,
            BecknFields.BAP_URI_V1,        BecknFields.BAP_URI,
            BecknFields.BPP_ID_V1,         BecknFields.BPP_ID,
            BecknFields.BPP_URI_V1,        BecknFields.BPP_URI,
            BecknFields.MESSAGE_ID_V1,     BecknFields.MESSAGE_ID,
            BecknFields.TRANSACTION_ID_V1, BecknFields.TRANSACTION_ID
    );

    /**
     * Normalizes a context JsonNode in-place: copies V1.0 snake_case fields
     * to their V2.0 camelCase equivalents when the V2.0 field is absent.
     *
     * @param contextNode the context node from the incoming message
     * @return the same node (mutated) for fluent chaining, or {@code null} if the node is not an object
     */
    public static ObjectNode normalize(JsonNode contextNode) {
        if (contextNode == null || contextNode.isMissingNode() || !contextNode.isObject()) {
            return contextNode instanceof ObjectNode on ? on : null;
        }
        ObjectNode ctx = (ObjectNode) contextNode;
        for (var entry : V1_TO_V2.entrySet()) {
            String v1Key = entry.getKey();
            String v2Key = entry.getValue();
            JsonNode v1Val = ctx.path(v1Key);
            if (!v1Val.isMissingNode() && ctx.path(v2Key).isMissingNode()) {
                ctx.set(v2Key, v1Val);
            }
        }
        return ctx;
    }
}
