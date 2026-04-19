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
    void mergeResourcePayload_patchOverwritesField() throws Exception {
        String existing = "{\"catalogs\":[{\"resources\":[{\"a\":1,\"b\":2}],\"offers\":[]}]}";
        JsonNode patch = mapper.createObjectNode().put("b", 99);
        JsonNode result = service.mergeResourcePayload(existing, patch);
        JsonNode item = result.path("catalogs").path(0).path("resources").path(0);
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
                .put("id", "offer-1")
                .putNull("someField");
        JsonNode result = service.stripNulls(node);
        assertThat(result.has("id")).isTrue();
        assertThat(result.path("id").asText()).isEqualTo("offer-1");
        assertThat(result.has("someField")).isFalse();
    }

    @Test
    void stripNulls_removesNestedNullField() {
        ObjectNode nested = mapper.createObjectNode()
                .put("name", "EV Station")
                .putNull("description");
        ObjectNode node = mapper.createObjectNode()
                .put("id", "item-1");
        node.set("descriptor", nested);
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("id").asText()).isEqualTo("item-1");
        assertThat(result.path("descriptor").path("name").asText()).isEqualTo("EV Station");
        assertThat(result.path("descriptor").has("description")).isFalse();
    }

    @Test
    void stripNulls_preservesNonNullFields() {
        ObjectNode node = mapper.createObjectNode()
                .put("id", "offer-1")
                .put("price", 100);
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("id").asText()).isEqualTo("offer-1");
        assertThat(result.path("price").asInt()).isEqualTo(100);
    }

    @Test
    void stripNulls_leavesArraysIntact() throws Exception {
        // Arrays are never recursed into — array elements are treated as
        // wholesale replacements, consistent with RFC 7396 array semantics.
        JsonNode node = mapper.readTree(
                "{\"resourceIds\":[\"item-1\",\"item-2\"],\"nullField\":null}");
        JsonNode result = service.stripNulls(node);
        assertThat(result.path("resourceIds").isArray()).isTrue();
        assertThat(result.path("resourceIds").size()).isEqualTo(2);
        assertThat(result.has("nullField")).isFalse();
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
                .put("id", "offer-1")
                .putNull("toRemove");
        service.stripNulls(node);
        // original must be unchanged — stripNulls works on a deep copy
        assertThat(node.has("toRemove")).isTrue();
    }

    // ── null-safe merge behaviour (the core upsert guarantee) ────────────────

    @Test
    void mergeResourcePayload_nullFieldInPatch_doesNotDeleteExistingData() throws Exception {
        // Stored item has id + descriptor with name + gps
        String existing = "{\"catalogs\":[{\"resources\":[{"
                + "\"id\":\"item-1\","
                + "\"descriptor\":{\"name\":\"EV Station\"},"
                + "\"gps\":\"12.34,56.78\""
                + "}],\"offers\":[]}]}";

        // Incoming publish sets name to null and omits gps — neither should delete stored data
        JsonNode itemPatch = mapper.readTree(
                "{\"id\":\"item-1\",\"descriptor\":{\"name\":null}}");
        JsonNode strippedPatch = service.stripNulls(itemPatch);
        JsonNode result = service.mergeResourcePayload(existing, strippedPatch);

        JsonNode mergedItem = result.path("catalogs").path(0).path("resources").path(0);
        // id must be preserved
        assertThat(mergedItem.path("id").asText()).isEqualTo("item-1");
        // null name was stripped → stored name is preserved
        assertThat(mergedItem.path("descriptor").path("name").asText()).isEqualTo("EV Station");
        // gps was absent in patch → preserved
        assertThat(mergedItem.path("gps").asText()).isEqualTo("12.34,56.78");
    }

    @Test
    void mergeOfferIntoPayload_nullFieldInOffer_doesNotDeleteExistingOfferData() throws Exception {
        // Stored payload with one offer that has id + resourceIds link + descriptor
        JsonNode payload = mapper.readTree(
                "{\"catalogs\":[{\"resources\":[{\"id\":\"item-1\"}],"
                        + "\"offers\":[{"
                        + "\"id\":\"offer-1\","
                        + "\"resourceIds\":[\"item-1\"],"
                        + "\"descriptor\":{\"name\":\"Offer One\"}"
                        + "}]}]}");

        // Incoming offer update: updates name to null (accidentally) — ID link must be preserved
        JsonNode incomingOffer = mapper.readTree(
                "{\"id\":\"offer-1\",\"descriptor\":{\"name\":null}}");
        JsonNode strippedOffer = service.stripNulls(incomingOffer);

        service.mergeOfferIntoPayload(payload, strippedOffer, "offer-1",
                service.buildOfferIndex(payload));

        JsonNode mergedOffer = payload.path("catalogs").path(0).path("offers").path(0);
        // id must be preserved
        assertThat(mergedOffer.path("id").asText()).isEqualTo("offer-1");
        // resourceIds link must be preserved — not deleted by null name field
        assertThat(mergedOffer.path("resourceIds").isArray()).isTrue();
        assertThat(mergedOffer.path("resourceIds").size()).isEqualTo(1);
        // null name was stripped → stored name is preserved
        assertThat(mergedOffer.path("descriptor").path("name").asText()).isEqualTo("Offer One");
    }
}
