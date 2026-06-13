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
 * Schema-context pairing guard for {@link SpatialQueryBuilder} — backs the
 * PostGIS routes: case 2 (G, {@link #build}), case 4 (J+G, {@link #buildCombined}),
 * and chain step 2 for case 7 (J+G+T, {@link #buildCombinedWithAllowlist}).
 *
 * <p>Regression: spec SC-45 / F-14 — see {@code JsonPathQueryBuilderTest}. Asserts
 * the spatial SQL emits per-pair {@code (context_url = ? AND type = ?)} predicates
 * OR'd together, never the independent {@code IN} form that lost pairing.</p>
 */
class SpatialQueryBuilderTest {

    private static final String GROCERY = "https://schema.org/Grocery";
    private static final String RETAIL  = "https://schema.org/Retail";

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

    private static List<String> twoPairs() {
        return List.of(GROCERY + "#GroceryResource", RETAIL + "#RetailResource");
    }

    @Test
    @DisplayName("spatial-only (case 2) pairs context+type, no independent IN")
    void build_pairsContextAndType() {
        Optional<QuerySpec> spec = builder.build(dwithin(), twoPairs(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql())
                .contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?)");
        assertThat(spec.get().sql()).doesNotContain("i.type IN").doesNotContain("i.context_url IN");
        assertThat(spec.get().parameters())
                .containsSubsequence(GROCERY, "GroceryResource", RETAIL, "RetailResource");
    }

    @Test
    @DisplayName("combined J+G (case 4) pairs context+type, no independent IN")
    void buildCombined_pairsContextAndType() {
        Optional<QuerySpec> spec = builder.buildCombined(
                dwithin(), "$.catalogs[*] ? (@.isActive == true)", twoPairs(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql())
                .contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?)");
        assertThat(spec.get().sql()).doesNotContain("i.type IN").doesNotContain("i.context_url IN");
        assertThat(spec.get().parameters())
                .containsSubsequence(GROCERY, "GroceryResource", RETAIL, "RetailResource");
    }

    @Test
    @DisplayName("combined J+G+T chain step 2 (buildCombinedWithAllowlist) pairs context+type + allowlist")
    void buildCombinedWithAllowlist_pairsAndAllowlists() {
        Optional<QuerySpec> spec = builder.buildCombinedWithAllowlist(
                dwithin(), "$.catalogs[*] ? (@.isActive == true)", twoPairs(), 100,
                List.of("res-1", "res-2"));

        assertThat(spec).isPresent();
        String sql = spec.get().sql();
        assertThat(sql).contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?)");
        assertThat(sql).doesNotContain("i.type IN").doesNotContain("i.context_url IN");
        assertThat(sql).contains("i.id = ANY(string_to_array(?, '|'))");
        assertThat(spec.get().parameters())
                .containsSubsequence(GROCERY, "GroceryResource", RETAIL, "RetailResource");
    }

    @Test
    @DisplayName("single pair → one paired predicate, no OR wrapper")
    void singlePair_noOrWrapper() {
        Optional<QuerySpec> spec = builder.build(dwithin(), List.of(GROCERY + "#GroceryResource"), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).contains("(i.context_url = ? AND i.type = ?)");
        assertThat(spec.get().sql()).doesNotContain(" OR (i.context_url");
    }

    @Test
    @DisplayName("context-only entry → context predicate only")
    void contextOnly_matchesAnyType() {
        Optional<QuerySpec> spec = builder.build(dwithin(), List.of(GROCERY), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).contains("(i.context_url = ?)");
    }

    @Test
    @DisplayName("no schemaContext → no context/type predicate")
    void noSchemaContext_noSchemaPredicate() {
        Optional<QuerySpec> spec = builder.build(dwithin(), List.of(), 100);

        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).doesNotContain("i.context_url");
    }
}
