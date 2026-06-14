package org.beckn.discover.service.postgresql.spatial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offer-narrowing parity guard (Finding 2): an offer-selection JSONPath filter
 * (e.g. {@code $.catalogs[*].offers[*] ? (...)}) must narrow the returned offers the
 * SAME way whether or not a spatial constraint is present.
 *
 * <p>Previously {@code buildCombined} / {@code buildCombinedWithAllowlist} always used
 * {@code BASE_SELECT}, so adding geo silently dropped the {@code matching_offers}
 * projection that {@code JsonPathQueryBuilder} uses — widening the response back to all
 * offers. These tests assert the combined paths now project {@code matching_offers} for
 * selection-path filters, with the processed filter bound first (param-order parity).</p>
 */
class SpatialQueryBuilderOfferNarrowingTest {

    private static final String OFFER_FILTER = "$.catalogs[*].offers[*] ? (@.offerAttributes.price < 20)";

    private SpatialQueryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SpatialQueryBuilder(new ObjectMapper(), new JsonPathConverter());
    }

    private static List<DiscoverRequest.SpatialConstraint> dwithin() {
        var geo = new DiscoverRequest.GeoJSONGeometry();
        geo.setType("Point");
        geo.setCoordinates(List.of(77.5, 12.9));
        var c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("s_dwithin");
        c.setGeometry(geo);
        c.setDistanceMeters(50000.0);
        c.setTargets("$.catalogs[*].provider.availableAt[*].geo");
        return List.of(c);
    }

    @Test
    @DisplayName("J+G with offer-selection filter projects matching_offers (offer narrowing preserved with geo)")
    void buildCombined_offerSelection_projectsMatchingOffers() {
        Optional<QuerySpec> spec = builder.buildCombined(dwithin(), OFFER_FILTER, List.of(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql())
                .contains("jsonb_path_query_array(i.payload, CAST(? AS jsonpath)) AS matching_offers");
        // processed selection path is bound FIRST (the SELECT projection placeholder)
        // the bound select param is the PROCESSED filter (== raw here since no colon/quote fields)
        assertThat(spec.get().parameters().get(0)).isEqualTo(new JsonPathConverter().processFilter(OFFER_FILTER));
    }

    @Test
    @DisplayName("J+G+T chain step 2 with offer-selection filter also projects matching_offers + keeps allowlist")
    void buildCombinedWithAllowlist_offerSelection_projectsMatchingOffersAndAllowlist() {
        Optional<QuerySpec> spec = builder.buildCombinedWithAllowlist(
                dwithin(), OFFER_FILTER, List.of(), 100, List.of("res-1", "res-2"));

        assertThat(spec).isPresent();
        String sql = spec.get().sql();
        assertThat(sql).contains("jsonb_path_query_array(i.payload, CAST(? AS jsonpath)) AS matching_offers");
        assertThat(sql).contains("i.id = ANY(string_to_array(?, '|'))");
        // the bound select param is the PROCESSED filter (== raw here since no colon/quote fields)
        assertThat(spec.get().parameters().get(0)).isEqualTo(new JsonPathConverter().processFilter(OFFER_FILTER));
    }

    @Test
    @DisplayName("geo-only (no filter) does NOT project matching_offers")
    void buildCombined_noFilter_usesBaseSelect() {
        Optional<QuerySpec> spec = builder.buildCombined(dwithin(), "", List.of(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).doesNotContain("matching_offers");
    }

    @Test
    @DisplayName("resource-selection path also projects matching_offers (assembler's isOfferLike ignores resource nodes)")
    void buildCombined_resourceSelection_projectsMatchingOffers() {
        // Any $-path takes the selection branch; the projected matching_offers will contain
        // resource objects, which PostgreSQLAssembler.isOfferLike() rejects — so offers are
        // not corrupted. Here we just assert the SQL takes the selection branch consistently.
        String resourceFilter = "$.catalogs[*].resources[*] ? (@.resourceAttributes.processingTimeDays == 5)";
        Optional<QuerySpec> spec = builder.buildCombined(dwithin(), resourceFilter, List.of(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql())
                .contains("jsonb_path_query_array(i.payload, CAST(? AS jsonpath)) AS matching_offers");
        assertThat(spec.get().parameters().get(0)).isEqualTo(new JsonPathConverter().processFilter(resourceFilter));
    }

    @Test
    @DisplayName("non-selection (relative @) filter falls back to BASE_SELECT but still applies the @@ predicate")
    void buildCombined_relativeFilter_noProjectionButPredicateApplied() {
        Optional<QuerySpec> spec = builder.buildCombined(
                dwithin(), "@.resourceAttributes.processingTimeDays == 5", List.of(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).doesNotContain("matching_offers");
        assertThat(spec.get().sql()).contains("i.payload @@ CAST(? AS jsonpath)");
    }

    @Test
    @DisplayName("full param order: selection + spatial(dwithin) + schema + allowlist line up with placeholders")
    void buildCombinedWithAllowlist_fullParamOrder() {
        Optional<QuerySpec> spec = builder.buildCombinedWithAllowlist(
                dwithin(), OFFER_FILTER, List.of("https://schema.org/Grocery#GroceryItem"),
                100, List.of("res-1", "res-2"));

        assertThat(spec).isPresent();
        var p = spec.get().parameters();
        // [0]=processed select filter, [1]=exists() predicate, then spatial (path, geojson, distance),
        // then schema pair (context, type), then allowlist (WHERE), then allowlist (ORDER BY)
        assertThat(p.get(0)).isEqualTo(new JsonPathConverter().processFilter(OFFER_FILTER));
        assertThat(p.get(1).toString()).contains("exists(");                 // JSONPATH_MATCH predicate
        assertThat(p).contains("$.catalogs[*].provider.availableAt[*].geo"); // spatial targets path
        // schema-context pairing (#302): base + fragment bound as a (context, type) pair
        assertThat(p).contains("https://schema.org/Grocery", "GroceryItem");
        // allowlist appears twice (WHERE ANY + ORDER BY array_position), pipe-joined
        assertThat(p.stream().filter(x -> "res-1|res-2".equals(x)).count()).isEqualTo(2L);
        // schema clause is the paired form, not independent INs
        assertThat(spec.get().sql()).contains("(i.context_url = ? AND i.type = ?)");
    }
}
