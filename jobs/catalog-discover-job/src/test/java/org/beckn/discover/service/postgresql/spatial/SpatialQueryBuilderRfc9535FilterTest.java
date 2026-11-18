package org.beckn.discover.service.postgresql.spatial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathQueryBuilder;
import org.beckn.discover.testsupport.Rfc9535TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: an RFC 9535 filter combined with a spatial constraint — J+G
 * ({@link SpatialQueryBuilder#buildCombined}) and the J+G+T chain step 2
 * ({@link SpatialQueryBuilder#buildCombinedWithAllowlist}) — must compile exactly as it does on
 * the filter-only route ({@link JsonPathQueryBuilder#build}).
 *
 * <p>Previously the combined builders bypassed {@code Rfc9535FilterCompiler} and bound the raw
 * expression as {@code CAST(? AS jsonpath)}, so every {@code [?(...)]} filter failed in Postgres
 * with {@code syntax error at or near "?" of jsonpath input} (HTTP 500) — while the same filter
 * worked without a spatial constraint. These tests pin the combined SQL to "the J-route SQL plus
 * the spatial EXISTS", for RFC 9535 and legacy filters alike.</p>
 */
class SpatialQueryBuilderRfc9535FilterTest {

    /** DEG EV example "within boundary with connection spec" (RFC 9535 canonical form). */
    private static final String RESOURCE_FILTER = "$.catalogs[*].resources[?(@.resourceAttributes.connectorType"
            + " == 'CCS2' && @.resourceAttributes.maxPowerKW >= 50)]";
    private static final String CATALOG_FILTER = "$.catalogs[?(@.provider.id == 'ecopower-charging')]";
    private static final String OFFER_FILTER = "$.catalogs[*].offers[?(@.validity.startTime <= '07:00:00'"
            + " && @.validity.endTime >= '21:00:00')]";
    /** RFC 9535 filter over the members of each resource object (see Rfc9535SqlPredicateCompiler.CHILDREN_OF). */
    private static final String FILTER_AFTER_WILDCARD =
            "$.catalogs[*].resources[*][?(@.resourceAttributes.connectorType == 'CCS2')]";
    /** Legacy Postgres-native jsonpath — must keep working on every route. */
    private static final String LEGACY_FILTER =
            "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")";

    private static final String TARGET = "$.catalogs[*].provider.availableAt[*].geo";
    private static final String SPATIAL_EXISTS = " AND EXISTS (SELECT 1 FROM item_location_collection ilc";
    private static final String RFC9535_LEVEL_0 =
            "EXISTS (SELECT 1 FROM jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0";

    private JsonPathQueryBuilder jsonPathQueryBuilder;
    private SpatialQueryBuilder builder;

    @BeforeEach
    void setUp() {
        jsonPathQueryBuilder = new JsonPathQueryBuilder(Rfc9535TestFixtures.alwaysLegacyValid());
        builder = new SpatialQueryBuilder(new ObjectMapper(), jsonPathQueryBuilder);
    }

    private static List<DiscoverRequest.SpatialConstraint> dwithin() {
        var geo = new DiscoverRequest.GeoJSONGeometry();
        geo.setType("Point");
        geo.setCoordinates(List.of(77.59, 12.94));
        var c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("s_dwithin");
        c.setGeometry(geo);
        c.setDistanceMeters(10000.0);
        c.setTargets(TARGET);
        return List.of(c);
    }

    @ParameterizedTest(name = "J+G compiles RFC 9535 filter to typed SQL: {0}")
    @ValueSource(strings = {RESOURCE_FILTER, CATALOG_FILTER, OFFER_FILTER, FILTER_AFTER_WILDCARD})
    @DisplayName("J+G: RFC 9535 filter is compiled to typed SQL, never bound as a Postgres jsonpath")
    void buildCombined_rfc9535Filter_neverCastToJsonpath(String filter) {
        QuerySpec spec = builder.buildCombined(dwithin(), filter, List.of(), 100).orElseThrow();

        assertThat(spec.sql()).contains(RFC9535_LEVEL_0);
        assertThat(spec.sql()).doesNotContain("AS jsonpath").doesNotContain("@@");
        assertThat(spec.parameters())
                .as("the raw expression must not be bound anywhere")
                .noneMatch(p -> p instanceof String s && s.contains("[?("));
    }

    @ParameterizedTest(name = "J+G SQL = J SQL + spatial EXISTS: {0}")
    @ValueSource(strings = {RESOURCE_FILTER, CATALOG_FILTER, OFFER_FILTER, FILTER_AFTER_WILDCARD, LEGACY_FILTER})
    @DisplayName("J+G: filter part of the SQL and its parameters are identical to the filter-only route")
    void buildCombined_filterPartIdenticalToJsonPathRoute(String filter) {
        QuerySpec filterOnly = jsonPathQueryBuilder.build(filter, List.of(), 100);
        QuerySpec combined = builder.buildCombined(dwithin(), filter, List.of(), 100).orElseThrow();

        assertFilterPrefixThenSpatial(filterOnly, combined);
    }

    @ParameterizedTest(name = "J+G+T chain step 2 SQL = J SQL + spatial EXISTS + allowlist: {0}")
    @ValueSource(strings = {RESOURCE_FILTER, CATALOG_FILTER, OFFER_FILTER, FILTER_AFTER_WILDCARD, LEGACY_FILTER})
    @DisplayName("J+G+T chain step 2: same filter SQL as the filter-only route, plus spatial and the id allowlist")
    void buildCombinedWithAllowlist_filterPartIdenticalToJsonPathRoute(String filter) {
        QuerySpec filterOnly = jsonPathQueryBuilder.build(filter, List.of(), 100);
        QuerySpec combined = builder.buildCombinedWithAllowlist(
                dwithin(), filter, List.of(), 100, List.of("res-1", "res-2")).orElseThrow();

        assertFilterPrefixThenSpatial(filterOnly, combined);
        assertThat(combined.sql()).contains("i.id = ANY(string_to_array(?, '|'))");
        assertThat(combined.parameters().stream().filter("res-1|res-2"::equals).count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("J+G: RFC 9535 offer filter keeps offer narrowing (RFC matching_offers projection, params first)")
    void buildCombined_rfc9535OfferFilter_projectsMatchingOffers() {
        QuerySpec spec = builder.buildCombined(dwithin(), OFFER_FILTER, List.of(), 100).orElseThrow();

        assertThat(spec.sql()).contains("SELECT jsonb_agg(po) FROM").contains(") AS matching_offers FROM item i");
        assertThat(spec.parameters().get(0)).isEqualTo(new String[]{"catalogs"});
        assertThat(spec.parameters().get(1)).isEqualTo(new String[]{"offers"});
    }

    @Test
    @DisplayName("J+G: RFC 9535 resource filter has no offers projection")
    void buildCombined_rfc9535ResourceFilter_noProjection() {
        QuerySpec spec = builder.buildCombined(dwithin(), RESOURCE_FILTER, List.of(), 100).orElseThrow();

        assertThat(spec.sql()).doesNotContain("matching_offers");
        // filter params: catalogs, resources, (field, literal) x 2 — then the spatial target path
        assertThat(spec.parameters().get(0)).isEqualTo(new String[]{"catalogs"});
        assertThat(spec.parameters().get(1)).isEqualTo(new String[]{"resources"});
        assertThat(spec.parameters().get(2)).isEqualTo(new String[]{"resourceAttributes", "connectorType"});
        assertThat(spec.parameters().get(3)).isEqualTo("CCS2");
        assertThat(spec.parameters().get(4)).isEqualTo(new String[]{"resourceAttributes", "maxPowerKW"});
        assertThat(spec.parameters().get(5)).isEqualTo(new java.math.BigDecimal("50"));
        assertThat(spec.parameters().get(6)).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("J+G: legacy Postgres-native filter still uses the legacy jsonpath path, unchanged")
    void buildCombined_legacyFilter_unchanged() {
        QuerySpec spec = builder.buildCombined(dwithin(), LEGACY_FILTER, List.of(), 100).orElseThrow();

        assertThat(spec.sql())
                .contains("jsonb_path_query_array(i.payload, CAST(? AS jsonpath)) AS matching_offers")
                .contains("i.payload @@ CAST(? AS jsonpath)");
        String processed = "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")";
        assertThat(spec.parameters().get(0)).isEqualTo(processed);
        assertThat(spec.parameters().get(1)).isEqualTo("exists(" + processed + ")");
        assertThat(spec.parameters().get(2)).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("J+G with a blank filter applies only the spatial condition")
    void buildCombined_blankFilter_spatialOnly() {
        QuerySpec spec = builder.buildCombined(dwithin(), "  ", List.of(), 100).orElseThrow();

        assertThat(spec.sql()).startsWith("SELECT i.id, i.catalog_id, i.payload AS resource_payload FROM item i WHERE EXISTS"
                + " (SELECT 1 FROM item_location_collection ilc");
        assertThat(spec.parameters().get(0)).isEqualTo(TARGET);
    }

    /**
     * Asserts {@code combined} = {@code filterOnly}'s SELECT + filter WHERE, then the spatial EXISTS,
     * with {@code filterOnly}'s parameters as the leading parameters.
     */
    private static void assertFilterPrefixThenSpatial(QuerySpec filterOnly, QuerySpec combined) {
        String filterOnlySql = filterOnly.sql();
        String selectAndFilter = filterOnlySql.substring(0, filterOnlySql.indexOf(" ORDER BY "));
        assertThat(combined.sql()).startsWith(selectAndFilter + SPATIAL_EXISTS);

        List<Object> leading = combined.parameters().subList(0, filterOnly.parameters().size());
        assertThat(leading).containsExactlyElementsOf(filterOnly.parameters());
        assertThat(combined.parameters().get(filterOnly.parameters().size())).isEqualTo(TARGET);
    }
}
