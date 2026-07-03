package org.beckn.discover.integration;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the opt-in {@code activeOnly} filter (catalog-level {@code isActive} +
 * {@code validity}), exercised against real PostgreSQL through {@link PostgreSQLQueryEngine}.
 *
 * <p>Proves the spec-faithful, null-safe semantics (core-v2.0.0-lts) and that filtering happens
 * <b>in-query</b>, composing independently with the #309 networkId scoping. The ES twin of the
 * predicate is unit-tested in {@code EsActiveValidityFilterBuilderTest}; the SQL predicate itself
 * in {@code QueryBuilderActiveValidityTest}. Validity windows use wide bounds (2020 / 2999) so the
 * outcome is independent of wall-clock "now".</p>
 */
class ActiveOnlyFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PostgreSQLQueryEngine pgQueryEngine;

    private static final String GEO_PATH = "$.catalogs[*].resources[*].availableAt[*].geo";
    private static final double LON = 77.5946;
    private static final double LAT = 12.9716;

    /**
     * Seeds one item = one single-resource catalog carrying the given tag.
     *
     * @param isActiveJson  e.g. {@code "\"isActive\":false,"} or {@code ""} (absent)
     * @param validityJson  e.g. {@code "\"validity\":{...},"} or {@code ""} (absent)
     */
    private void seed(String tag, String itemId, String catalogId, String network,
                      String isActiveJson, String validityJson) {
        String payload = ("""
                {"catalogs":[{
                  "id":"%s",
                  "descriptor":{"name":"Catalog %s"},
                  %s%s
                  "provider":{"id":"prov-%s","descriptor":{"name":"Provider"}},
                  "resources":[{"id":"%s","descriptor":{"name":"Resource"},
                    "resourceAttributes":{"tag":"%s"},
                    "availableAt":[{"geo":{"type":"Point","coordinates":[%s,%s]}}]}]
                }]}""")
                .formatted(catalogId, catalogId, isActiveJson, validityJson,
                        catalogId, itemId, tag, LON, LAT);
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO item (id, catalog_id, context_url, type, network_id, offer_ids, payload, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ARRAY[]::TEXT[], ?, NOW()) "
                            + "ON CONFLICT (id, catalog_id) DO UPDATE SET payload = EXCLUDED.payload, network_id = EXCLUDED.network_id");
            ps.setString(1, itemId);
            ps.setString(2, catalogId);
            ps.setString(3, "https://schema.beckn.io/GroceryResource/v2.1/context.jsonld");
            ps.setString(4, "groc:GroceryResource");
            ps.setArray(5, connection.createArrayOf("text", new String[]{network}));
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(payload);
            ps.setObject(6, jsonb);
            return ps;
        });
    }

    private void seedGeom(String itemId, String catalogId) {
        jdbcTemplate.update(
                "INSERT INTO item_location_collection (item_id, catalog_id, path, geom) "
                        + "VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)) "
                        + "ON CONFLICT (item_id, catalog_id, path) DO UPDATE SET geom = EXCLUDED.geom",
                itemId, catalogId, GEO_PATH, LON, LAT);
    }

    private static final String VALID_WINDOW  = "\"validity\":{\"startDate\":\"2020-01-01T00:00:00Z\",\"endDate\":\"2999-12-31T23:59:59Z\"},";
    private static final String EXPIRED_WINDOW = "\"validity\":{\"startDate\":\"2019-01-01T00:00:00Z\",\"endDate\":\"2020-12-31T23:59:59Z\"},";
    private static final String FUTURE_WINDOW  = "\"validity\":{\"startDate\":\"2999-01-01T00:00:00Z\"},";
    private static final String OPEN_START     = "\"validity\":{\"startDate\":\"2020-01-01T00:00:00Z\"},";
    private static final String OPEN_END       = "\"validity\":{\"endDate\":\"2999-12-31T23:59:59Z\"},";

    private QueryRequest jsonPathQuery(String tag, boolean activeOnly, String network) {
        DiscoverRequest req = new DiscoverRequest();
        var ctx = buildContext("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        ctx.setNetworkId(network);
        req.setContext(ctx);
        req.setFilters("$.catalogs[*].resources[*] ? (@.resourceAttributes.tag == \"" + tag + "\")");
        // Map the legacy single flag onto the value-match API: activeOnly ⇒ active=TRUE + validity=TRUE.
        return QueryRequest.from(req, activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null);
    }

    private QueryRequest jsonPathAndSpatialQuery(String tag, boolean activeOnly) {
        QueryRequest base = jsonPathQuery(tag, activeOnly, DEFAULT_TEST_NETWORK);
        var geo = new DiscoverRequest.GeoJSONGeometry();
        geo.setType("Point");
        geo.setCoordinates(List.of(LON, LAT));
        var sc = new DiscoverRequest.SpatialConstraint();
        sc.setOperation("s_dwithin");
        sc.setGeometry(geo);
        sc.setDistanceMeters(50000.0);
        // rebuild carrying the spatial constraint + the value-match flags (activeOnly ⇒ active=TRUE + validity=TRUE)
        return new QueryRequest(base.transactionId(), base.messageId(), base.filters(),
                List.of(sc), null, base.schemaTypes(), base.schemaContextUrls(),
                base.rawSchemaContextUrls(), base.networkId(),
                activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null);
    }

    // ── J: full semantics matrix ──────────────────────────────────────────────

    private void seedMatrix() {
        String net = DEFAULT_TEST_NETWORK;
        seed("matrix", "r-av",  "cat-active-valid", net, "",              VALID_WINDOW);   // keep
        seed("matrix", "r-in",  "cat-inactive",     net, "\"isActive\":false,", "");       // drop
        seed("matrix", "r-exp", "cat-expired",      net, "",              EXPIRED_WINDOW); // drop
        seed("matrix", "r-fut", "cat-future",       net, "",              FUTURE_WINDOW);  // drop
        seed("matrix", "r-no",  "cat-nofields",     net, "",              "");             // keep
        seed("matrix", "r-os",  "cat-open-start",   net, "",              OPEN_START);     // keep
        seed("matrix", "r-oe",  "cat-open-end",     net, "",              OPEN_END);       // keep
        seed("matrix", "r-at",  "cat-active-true",  net, "\"isActive\":true,",  "");        // keep
    }

    @Test
    @DisplayName("activeOnly=false returns ALL catalogs (existing behaviour unchanged)")
    void jsonPath_activeOnlyFalse_returnsAll() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(jsonPathQuery("matrix", false, DEFAULT_TEST_NETWORK));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active-valid", "cat-inactive", "cat-expired",
                        "cat-future", "cat-nofields", "cat-open-start", "cat-open-end", "cat-active-true");
    }

    @Test
    @DisplayName("activeOnly=true drops explicit-inactive, expired and not-yet-valid; keeps active/absent/open-ended")
    void jsonPath_activeOnlyTrue_dropsInactiveAndOutOfWindow() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(jsonPathQuery("matrix", true, DEFAULT_TEST_NETWORK));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active-valid", "cat-nofields", "cat-open-start",
                        "cat-open-end", "cat-active-true");
        assertThat(catalogs).extracting(Catalog::getId)
                .doesNotContain("cat-inactive", "cat-expired", "cat-future");
    }

    // ── J+G combined ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("J+G combined: activeOnly=true drops the expired catalog even when it matches the geo constraint")
    void combined_activeOnly_dropsExpired() throws Exception {
        seed("jg", "jg-av",  "jg-active", DEFAULT_TEST_NETWORK, "", VALID_WINDOW);
        seed("jg", "jg-exp", "jg-expired", DEFAULT_TEST_NETWORK, "", EXPIRED_WINDOW);
        seedGeom("jg-av", "jg-active");
        seedGeom("jg-exp", "jg-expired");

        Optional<List<Catalog>> all = pgQueryEngine.executeCombinedQuery(jsonPathAndSpatialQuery("jg", false));
        assertThat(all).isPresent();
        assertThat(all.get()).extracting(Catalog::getId).containsExactlyInAnyOrder("jg-active", "jg-expired");

        Optional<List<Catalog>> active = pgQueryEngine.executeCombinedQuery(jsonPathAndSpatialQuery("jg", true));
        assertThat(active).isPresent();
        assertThat(active.get()).extracting(Catalog::getId).containsExactly("jg-active");
    }

    // ── G spatial-only ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("G spatial-only: activeOnly=true drops the inactive catalog matched by geometry")
    void spatial_activeOnly_dropsInactive() throws Exception {
        seed("geo", "geo-av", "geo-active",   DEFAULT_TEST_NETWORK, "", VALID_WINDOW);
        seed("geo", "geo-in", "geo-inactive", DEFAULT_TEST_NETWORK, "\"isActive\":false,", "");
        seedGeom("geo-av", "geo-active");
        seedGeom("geo-in", "geo-inactive");

        var geo = new DiscoverRequest.GeoJSONGeometry();
        geo.setType("Point");
        geo.setCoordinates(List.of(LON, LAT));
        var sc = new DiscoverRequest.SpatialConstraint();
        sc.setOperation("s_dwithin");
        sc.setGeometry(geo);
        sc.setDistanceMeters(50000.0);

        QueryRequest active = new QueryRequest("tx", "msg", null, List.of(sc), null,
                List.of(), List.of(), List.of(), DEFAULT_TEST_NETWORK, Boolean.TRUE, Boolean.TRUE);
        List<Catalog> catalogs = pgQueryEngine.executeSpatialQuery(active);
        assertThat(catalogs).extracting(Catalog::getId).contains("geo-active");
        assertThat(catalogs).extracting(Catalog::getId).doesNotContain("geo-inactive");
    }

    // ── Compose with networkId (independent predicates) ──────────────────────────

    @Test
    @DisplayName("activeOnly composes with networkId: only the requesting network's ACTIVE catalog is returned")
    void composesWithNetworkId() throws Exception {
        String netA = "net.example/alpha";
        String netB = "net.example/bravo";
        seed("compose", "c-a-act", "cat-a-active",   netA, "", VALID_WINDOW);          // keep (net A, active)
        seed("compose", "c-a-in",  "cat-a-inactive", netA, "\"isActive\":false,", ""); // drop (net A, inactive)
        seed("compose", "c-b-act", "cat-b-active",   netB, "", VALID_WINDOW);          // drop (wrong network)

        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(jsonPathQuery("compose", true, netA));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("cat-a-active");
    }

    // ── Value-match: false-direction and independent dimensions ──────────────────

    /** Value-match query with explicit per-dimension flags (null ⇒ that dimension not filtered). */
    private QueryRequest jsonPathQuery(String tag, String network, Boolean active, Boolean validity) {
        DiscoverRequest req = new DiscoverRequest();
        var ctx = buildContext("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        ctx.setNetworkId(network);
        req.setContext(ctx);
        req.setFilters("$.catalogs[*].resources[*] ? (@.resourceAttributes.tag == \"" + tag + "\")");
        return QueryRequest.from(req, active, validity);
    }

    @Test
    @DisplayName("active=false returns ONLY the explicitly inactive catalog (absent isActive counts active)")
    void active_false_returnsInactiveOnly() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("matrix", DEFAULT_TEST_NETWORK, Boolean.FALSE, null));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("cat-inactive");
    }

    @Test
    @DisplayName("validity=false returns ONLY provably out-of-window catalogs (expired + future); absent/open-ended count valid")
    void validity_false_returnsOutOfWindowOnly() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("matrix", DEFAULT_TEST_NETWORK, null, Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-expired", "cat-future");
    }

    @Test
    @DisplayName("active=true + validity=false → active-or-absent AND out-of-window (expired/future, not the inactive one)")
    void active_true_validity_false() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("matrix", DEFAULT_TEST_NETWORK, Boolean.TRUE, Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-expired", "cat-future");
    }

    @Test
    @DisplayName("active=false + validity=true → inactive AND currently-valid (only the inactive-with-no-window catalog)")
    void active_false_validity_true() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("matrix", DEFAULT_TEST_NETWORK, Boolean.FALSE, Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("cat-inactive");
    }

    @Test
    @DisplayName("both omitted (null) returns ALL — dimension-not-filtered semantics")
    void bothNull_returnsAll() throws Exception {
        seedMatrix();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("matrix", DEFAULT_TEST_NETWORK, null, null));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active-valid", "cat-inactive", "cat-expired",
                        "cat-future", "cat-nofields", "cat-open-start", "cat-open-end", "cat-active-true");
    }
}
