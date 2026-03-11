package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadMergeServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadMergeService service = new PayloadMergeService(mapper);

    @Test
    void mergeItemPayload_patchOverwritesField() throws Exception {
        String existing = "{\"catalogs\":[{\"beckn:items\":[{\"a\":1,\"b\":2}],\"beckn:offers\":[]}]}";
        JsonNode patch = mapper.createObjectNode().put("b", 99);
        JsonNode result = service.mergeItemPayload(existing, patch);
        JsonNode item = result.path("catalogs").path(0).path("beckn:items").path(0);
        assertThat(item.path("a").asInt()).isEqualTo(1);
        assertThat(item.path("b").asInt()).isEqualTo(99);
    }

    @Test
    void parseOrEmpty_blankReturnsEmptyObject() {
        JsonNode n = service.parseOrEmpty(null);
        assertThat(n.isObject()).isTrue();
        assertThat(n.isEmpty()).isTrue();
    }

    // ── stripNulls unit tests ────────────────────────────────────────────────

    @Test
    void stripNulls_removesTopLevelNullField() {
        ObjectNode node = mapper.createObjectNode()
                .put("beckn:id", "offer-1")
                .putNull("beckn:someField");
        JsonNode result = service.stripNulls(node);
        assertThat(result.has("beckn:id")).isTrue();
        assertThat(result.path("beckn:id").asText()).isEqualTo("offer-1");
        assertThat(result.has("beckn:someField")).isFalse();
    }

    @Test
    void stripNulls_removesNestedNullField() {
        ObjectNode nested = mapper.createObjectNode()
                .put("schema:name", "EV Station")
                .putNull("schema:description");
        ObjectNode node = mapper.createObjectNode()
                .put("beckn:id", "item-1");
        node.set("beckn:descriptor", nested);
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("beckn:id").asText()).isEqualTo("item-1");
        assertThat(result.path("beckn:descriptor").path("schema:name").asText()).isEqualTo("EV Station");
        assertThat(result.path("beckn:descriptor").has("schema:description")).isFalse();
    }

    @Test
    void stripNulls_preservesNonNullFields() {
        ObjectNode node = mapper.createObjectNode()
                .put("beckn:id", "offer-1")
                .put("beckn:price", 100);
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("beckn:id").asText()).isEqualTo("offer-1");
        assertThat(result.path("beckn:price").asInt()).isEqualTo(100);
    }

    @Test
    void stripNulls_leavesArraysIntact() throws Exception {
        // Arrays are never recursed into — array elements are treated as
        // wholesale replacements, consistent with RFC 7396 array semantics.
        JsonNode node = mapper.readTree(
                "{\"beckn:items\":[\"item-1\",\"item-2\"],\"beckn:nullField\":null}");
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("beckn:items").isArray()).isTrue();
        assertThat(result.path("beckn:items").size()).isEqualTo(2);
        assertThat(result.has("beckn:nullField")).isFalse();
    }

    @Test
    void stripNulls_returnsNonObjectNodeUnchanged() throws Exception {
        JsonNode array = mapper.readTree("[1,2,3]");
        assertThat(service.stripNulls(array)).isSameAs(array);
        assertThat(service.stripNulls(null)).isNull();
    }

    @Test
    void stripNulls_doesNotModifyOriginalNode() {
        ObjectNode node = mapper.createObjectNode()
                .put("beckn:id", "offer-1")
                .putNull("beckn:toRemove");
        service.stripNulls(node);
        // original must be unchanged — stripNulls works on a deep copy
        assertThat(node.has("beckn:toRemove")).isTrue();
    }

    // ── null-safe merge behaviour (the core upsert guarantee) ────────────────

    @Test
    void mergeItemPayload_nullFieldInPatch_doesNotDeleteExistingData() throws Exception {
        // Stored item has beckn:id + descriptor with name + gps
        String existing = "{\"catalogs\":[{\"beckn:items\":[{"
                + "\"beckn:id\":\"item-1\","
                + "\"beckn:descriptor\":{\"schema:name\":\"EV Station\"},"
                + "\"gps\":\"12.34,56.78\""
                + "}],\"beckn:offers\":[]}]}";

        // Incoming publish sets name to null and omits gps — neither should delete
        // stored data
        JsonNode itemPatch = mapper.readTree(
                "{\"beckn:id\":\"item-1\",\"beckn:descriptor\":{\"schema:name\":null}}");
        JsonNode strippedPatch = service.stripNulls(itemPatch);
        JsonNode result = service.mergeItemPayload(existing, strippedPatch);

        JsonNode mergedItem = result.path("catalogs").path(0).path("beckn:items").path(0);
        // beckn:id must be preserved
        assertThat(mergedItem.path("beckn:id").asText()).isEqualTo("item-1");
        // null schema:name was stripped → stored name is preserved
        assertThat(mergedItem.path("beckn:descriptor").path("schema:name").asText()).isEqualTo("EV Station");
        // gps was absent in patch → preserved
        assertThat(mergedItem.path("gps").asText()).isEqualTo("12.34,56.78");
    }

    @Test
    void mergeOfferIntoPayload_nullFieldInOffer_doesNotDeleteExistingOfferData() throws Exception {
        // Stored payload with one offer that has beckn:id + beckn:items link +
        // descriptor
        JsonNode payload = mapper.readTree(
                "{\"catalogs\":[{\"beckn:items\":[{\"beckn:id\":\"item-1\"}],"
                        + "\"beckn:offers\":[{"
                        + "\"beckn:id\":\"offer-1\","
                        + "\"beckn:items\":[\"item-1\"],"
                        + "\"beckn:descriptor\":{\"schema:name\":\"Offer One\"}"
                        + "}]}]}");

        // Incoming offer update: updates name to null (accidentally) — ID link must be
        // preserved
        JsonNode incomingOffer = mapper.readTree(
                "{\"beckn:id\":\"offer-1\",\"beckn:descriptor\":{\"schema:name\":null}}");
        JsonNode strippedOffer = service.stripNulls(incomingOffer);

        service.mergeOfferIntoPayload(payload, strippedOffer, "offer-1",
                service.buildOfferIndex(payload));

        JsonNode mergedOffer = payload.path("catalogs").path(0).path("beckn:offers").path(0);
        // beckn:id must be preserved
        assertThat(mergedOffer.path("beckn:id").asText()).isEqualTo("offer-1");
        // beckn:items link must be preserved — not deleted by null name field
        assertThat(mergedOffer.path("beckn:items").isArray()).isTrue();
        assertThat(mergedOffer.path("beckn:items").size()).isEqualTo(1);
        // null name was stripped → stored name is preserved
        assertThat(mergedOffer.path("beckn:descriptor").path("schema:name").asText()).isEqualTo("Offer One");
    }
}
