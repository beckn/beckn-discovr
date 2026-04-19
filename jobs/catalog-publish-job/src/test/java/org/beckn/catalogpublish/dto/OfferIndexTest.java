package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfferIndexTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void build_offersWithResourceIds_classifiedAsItemLevel() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o1", "resourceIds": ["r1", "r2"]},
                  {"id": "o2", "resourceIds": ["r3"]}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);

        assertThat(index.providerOffers()).isEmpty();
        assertThat(index.offersByItemId()).hasSize(3);
        assertThat(index.offersByItemId().get("r1")).hasSize(1);
        assertThat(index.offersByItemId().get("r2")).hasSize(1);
        assertThat(index.offersByItemId().get("r3")).hasSize(1);
    }

    @Test
    void build_offersWithoutResourceIds_classifiedAsProviderLevel() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o1", "descriptor": {"name": "Provider Offer 1"}},
                  {"id": "o2", "descriptor": {"name": "Provider Offer 2"}}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);

        assertThat(index.providerOffers()).hasSize(2);
        assertThat(index.offersByItemId()).isEmpty();
    }

    @Test
    void build_mixedOffers_classifiedCorrectly() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o-item", "resourceIds": ["r1"]},
                  {"id": "o-provider", "descriptor": {"name": "Buy 1 Get 1"}}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);

        assertThat(index.providerOffers()).hasSize(1);
        assertThat(index.providerOffers().get(0).path("id").asText()).isEqualTo("o-provider");
        assertThat(index.offersByItemId()).hasSize(1);
        assertThat(index.offersByItemId().get("r1")).hasSize(1);
    }

    @Test
    void getOffersForItem_returnsOnlyItemSpecificOffers() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o-item", "resourceIds": ["r1"]},
                  {"id": "o-provider", "descriptor": {"name": "Buy 1 Get 1"}}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);

        ArrayNode forR1 = index.getOffersForItem("r1", mapper);
        assertThat(forR1).hasSize(1);
        assertThat(forR1.get(0).path("id").asText()).isEqualTo("o-item");

        ArrayNode forUnknown = index.getOffersForItem("unknown-item", mapper);
        assertThat(forUnknown).isEmpty();
    }

    @Test
    void build_emptyResourceIds_classifiedAsProviderLevel() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o1", "resourceIds": [], "descriptor": {"name": "Empty resourceIds"}},
                  {"id": "o2", "resourceIds": ["r1"]}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);

        assertThat(index.providerOffers()).hasSize(1);
        assertThat(index.providerOffers().get(0).path("id").asText()).isEqualTo("o1");
        assertThat(index.offersByItemId()).hasSize(1);
        assertThat(index.offersByItemId().get("r1")).hasSize(1);
    }

    @Test
    void build_nullOffers_returnsEmptyIndex() {
        var index = OfferIndex.build(null, mapper);
        assertThat(index.providerOffers()).isEmpty();
        assertThat(index.offersByItemId()).isEmpty();
    }

    @Test
    void build_emptyArray_returnsEmptyIndex() throws Exception {
        var index = OfferIndex.build(mapper.readTree("[]"), mapper);
        assertThat(index.providerOffers()).isEmpty();
        assertThat(index.offersByItemId()).isEmpty();
    }

    @Test
    void getOffersForItem_stripsNullFields() throws Exception {
        JsonNode offers = mapper.readTree("""
                [
                  {"id": "o1", "resourceIds": ["r1"], "discount": null, "name": "valid"}
                ]
                """);
        var index = OfferIndex.build(offers, mapper);
        ArrayNode result = index.getOffersForItem("r1", mapper);

        assertThat(result).hasSize(1);
        JsonNode offer = result.get(0);
        assertThat(offer.has("discount")).isFalse();
        assertThat(offer.path("name").asText()).isEqualTo("valid");
    }
}
