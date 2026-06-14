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
 * Regression guard: the PostGIS spatial EXISTS join must be scoped by BOTH
 * {@code item_id} AND {@code catalog_id}.
 *
 * <p>The {@code item} PK is {@code (id, catalog_id)} and the same resource id may be
 * published in multiple catalogs at different locations
 * ({@code item_location_collection} has one geo row per (item_id, catalog_id)). A
 * join on {@code item_id} alone let a catalog match another catalog's geo for the same
 * resource id — e.g. a Pune catalog matching a Delhi radius because a Delhi catalog
 * sells the same service. The join must include {@code ilc.catalog_id = i.catalog_id}
 * so each catalog's spatial match is confined to its own locations (matching the ES
 * path, where each catalogId:resourceId document carries only its own geo).</p>
 */
class SpatialQueryBuilderCatalogScopeTest {

    private static final String CATALOG_JOIN = "ilc.item_id = i.id AND ilc.catalog_id = i.catalog_id";

    private SpatialQueryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SpatialQueryBuilder(new ObjectMapper(), new JsonPathConverter());
    }

    private static List<DiscoverRequest.SpatialConstraint> dwithin() {
        var geo = new DiscoverRequest.GeoJSONGeometry();
        geo.setType("Point");
        geo.setCoordinates(List.of(77.1678, 28.6378));
        var c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("s_dwithin");
        c.setGeometry(geo);
        c.setDistanceMeters(50000.0);
        c.setTargets("$.catalogs[*].provider.availableAt[*].geo");
        return List.of(c);
    }

    @Test
    @DisplayName("spatial-only build() scopes the EXISTS join by item_id AND catalog_id")
    void build_scopedByCatalog() {
        Optional<QuerySpec> spec = builder.build(dwithin(), List.of(), 100);
        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).contains(CATALOG_JOIN);
    }

    @Test
    @DisplayName("combined J+G buildCombined() scopes the EXISTS join by item_id AND catalog_id")
    void buildCombined_scopedByCatalog() {
        Optional<QuerySpec> spec = builder.buildCombined(
                dwithin(), "$.catalogs[*] ? (@.isActive == true)", List.of(), 100);
        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).contains(CATALOG_JOIN);
    }

    @Test
    @DisplayName("chain J+G+T buildCombinedWithAllowlist() scopes the EXISTS join by item_id AND catalog_id")
    void buildCombinedWithAllowlist_scopedByCatalog() {
        Optional<QuerySpec> spec = builder.buildCombinedWithAllowlist(
                dwithin(), "$.catalogs[*] ? (@.isActive == true)", List.of(), 100,
                List.of("res-1", "res-2"));
        assertThat(spec).isPresent();
        assertThat(spec.get().sql()).contains(CATALOG_JOIN);
    }

    @Test
    @DisplayName("join is never item_id-only (the bug that leaked geo across catalogs)")
    void neverItemIdOnlyJoin() {
        QuerySpec spec = builder.build(dwithin(), List.of(), 100).orElseThrow();
        // must not contain the bare item_id join immediately followed by a path/geo condition
        assertThat(spec.sql()).doesNotContain("ilc.item_id = i.id AND ST_");
        assertThat(spec.sql()).doesNotContain("ilc.item_id = i.id AND ilc.path = ? AND ST_DWithin(ilc.geom");
    }
}
