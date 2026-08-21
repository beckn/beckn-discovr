package org.beckn.discover.integration;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
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
 * outcome is independent of wall-clock "now". All queries carry a fixed {@link #FIXED_NOW} (noon
 * UTC) rather than the real wall clock — the wide date bounds don't care, but the startTime/
 * endTime time-of-day fallback tests need a controlled reference instant to assert specific
 * in/out-of-window outcomes deterministically.</p>
 */
class ActiveOnlyFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PostgreSQLQueryEngine pgQueryEngine;

    private static final String GEO_PATH = "$.catalogs[*].resources[*].availableAt[*].geo";
    private static final double LON = 77.5946;
    private static final double LAT = 12.9716;

    /** Noon UTC — arbitrary but fixed, so startTime/endTime window tests are deterministic. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T12:00:00Z");

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
    // Edge cases — time-of-day only (no date/zone) and an unparseable date. Both count as VALID
    // (not evaluable to an instant ⇒ never dropped by validity=true, never selected by validity=false).
    private static final String BARE_TIME      = "\"validity\":{\"startTime\":\"09:00:00\",\"endTime\":\"17:00:00\"},";
    private static final String MALFORMED      = "\"validity\":{\"startDate\":\"not-a-date\"},";
    // startTime/endTime windows, all evaluated against FIXED_NOW = 12:00:00 UTC.
    private static final String TIME_SAME_DAY_COVERING     = "\"validity\":{\"startTime\":\"09:00:00\",\"endTime\":\"21:00:00\"},";
    private static final String TIME_SAME_DAY_NOT_COVERING = "\"validity\":{\"startTime\":\"13:00:00\",\"endTime\":\"18:00:00\"},";
    private static final String TIME_WRAP_COVERING         = "\"validity\":{\"startTime\":\"10:00:00\",\"endTime\":\"08:00:00\"},"; // spans midnight, covers noon via the 10:00->24:00 segment
    private static final String TIME_WRAP_NOT_COVERING     = "\"validity\":{\"startTime\":\"20:00:00\",\"endTime\":\"06:00:00\"},"; // spans midnight, noon falls in neither segment
    // Both startDate (future, not-yet-valid) AND a covering startTime/endTime — proves date wins.
    private static final String DATE_WINS_OVER_TIME =
            "\"validity\":{\"startDate\":\"2099-01-01T00:00:00Z\",\"startTime\":\"09:00:00\",\"endTime\":\"21:00:00\"},";

    private QueryRequest jsonPathQuery(String tag, boolean activeOnly, String network) {
        DiscoverRequest req = new DiscoverRequest();
        var ctx = buildContext("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        ctx.setNetworkId(network);
        req.setContext(ctx);
        req.setFilters("$.catalogs[*].resources[*] ? (@.resourceAttributes.tag == \"" + tag + "\")");
        // Map the legacy single flag onto the value-match API: activeOnly ⇒ active=TRUE + validity=TRUE.
        return QueryRequest.from(req, activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null, FIXED_NOW);
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
        // rebuild carrying the spatial constraint + the value-match flags (activeOnly ⇒ active=TRUE + validity=TRUE),
        // propagating base.now() so the combined query shares the same reference instant as the base.
        return new QueryRequest(base.transactionId(), base.messageId(), base.filters(),
                List.of(sc), null, base.schemaTypes(), base.schemaContextUrls(),
                base.rawSchemaContextUrls(), base.networkId(),
                activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null, base.now());
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
        return QueryRequest.from(req, active, validity, FIXED_NOW);
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

    // ── Edge cases: bare time-of-day, unparseable date, inactive+expired ─────────

    /**
     * Seeds an "edge" tag set:
     *   e-baretime         — active (absent), validity is time-of-day only → counts VALID
     *   e-malformed        — active (absent), validity date unparseable     → counts VALID
     *   e-inactive-expired — explicitly inactive AND validity window expired → inactive + out-of-window
     */
    private void seedEdge() {
        String net = DEFAULT_TEST_NETWORK;
        seed("edge", "e-bt",  "e-baretime",         net, "",                    BARE_TIME);
        seed("edge", "e-mf",  "e-malformed",        net, "",                    MALFORMED);
        seed("edge", "e-ie",  "e-inactive-expired", net, "\"isActive\":false,", EXPIRED_WINDOW);
    }

    @Test
    @DisplayName("validity=true keeps bare-time & unparseable-date catalogs (not evaluable ⇒ valid); drops the expired one")
    void validity_true_keepsNonEvaluable() throws Exception {
        seedEdge();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("edge", DEFAULT_TEST_NETWORK, null, Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("e-baretime", "e-malformed");
    }

    @Test
    @DisplayName("validity=false selects ONLY the provably-expired catalog; bare-time & unparseable are NOT out-of-window")
    void validity_false_excludesNonEvaluable() throws Exception {
        seedEdge();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("edge", DEFAULT_TEST_NETWORK, null, Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("e-inactive-expired");
    }

    @Test
    @DisplayName("active=false selects ONLY the explicitly-inactive catalog (bare-time & unparseable are active-by-absence)")
    void active_false_edge() throws Exception {
        seedEdge();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("edge", DEFAULT_TEST_NETWORK, Boolean.FALSE, null));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("e-inactive-expired");
    }

    @Test
    @DisplayName("active=true + validity=true (the default) keeps only the valid, active-by-absence catalogs")
    void active_true_validity_true_edge() throws Exception {
        seedEdge();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("edge", DEFAULT_TEST_NETWORK, Boolean.TRUE, Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("e-baretime", "e-malformed");
    }

    @Test
    @DisplayName("active=false + validity=false selects the catalog that is BOTH inactive AND out-of-window")
    void active_false_validity_false_edge() throws Exception {
        seedEdge();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("edge", DEFAULT_TEST_NETWORK, Boolean.FALSE, Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId).containsExactly("e-inactive-expired");
    }

    // ── Date-shaped-but-invalid values must NOT crash the query (PR #397 review) ──

    /** Seeds catalogs whose validity dates are date-SHAPED but uncastable — the crash cases the review reproduced. */
    private void seedBadShape() {
        String net = DEFAULT_TEST_NETWORK;
        // month/day out of range — passes a shape regex but ::timestamptz would throw
        seed("badshape", "bs1", "bad-out-of-range", net, "", "\"validity\":{\"endDate\":\"2020-13-45T00:00:00Z\"},");
        // trailing garbage after a valid-looking date prefix
        seed("badshape", "bs2", "bad-trailing",     net, "", "\"validity\":{\"startDate\":\"2020-01-01garbage\"},");
    }

    @Test
    @DisplayName("validity=true: date-shaped-but-invalid values do NOT 500 and count as valid (kept)")
    void dateShapedInvalid_validityTrue_keptNotCrash() throws Exception {
        seedBadShape();
        // Must not throw (try_to_timestamptz returns NULL instead of erroring the cast).
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("badshape", DEFAULT_TEST_NETWORK, null, Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("bad-out-of-range", "bad-trailing");
    }

    @Test
    @DisplayName("validity=false: date-shaped-but-invalid values are NOT out-of-window (excluded), no 500")
    void dateShapedInvalid_validityFalse_excluded() throws Exception {
        seedBadShape();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("badshape", DEFAULT_TEST_NETWORK, null, Boolean.FALSE));
        assertThat(catalogs).isEmpty();
    }

    // ── startTime/endTime fallback: priority + UTC wrap-around (all vs FIXED_NOW = 12:00 UTC) ────

    private void seedTimeOfDay() {
        String net = DEFAULT_TEST_NETWORK;
        seed("tod", "t-sc", "time-same-day-covering",     net, "", TIME_SAME_DAY_COVERING);
        seed("tod", "t-snc", "time-same-day-not-covering", net, "", TIME_SAME_DAY_NOT_COVERING);
        seed("tod", "t-wc", "time-wrap-covering",          net, "", TIME_WRAP_COVERING);
        seed("tod", "t-wnc", "time-wrap-not-covering",     net, "", TIME_WRAP_NOT_COVERING);
        seed("tod", "t-dw", "date-wins-over-time",         net, "", DATE_WINS_OVER_TIME);
    }

    @Test
    @DisplayName("validity=true keeps same-day and wrap-around windows that cover noon; drops the ones that don't")
    void timeOfDay_validityTrue_keepsCoveringWindows() throws Exception {
        seedTimeOfDay();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("tod", DEFAULT_TEST_NETWORK, null, Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("time-same-day-covering", "time-wrap-covering");
    }

    @Test
    @DisplayName("validity=false selects the windows that do NOT cover noon (same-day and wrap-around)")
    void timeOfDay_validityFalse_selectsNonCoveringWindows() throws Exception {
        seedTimeOfDay();
        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("tod", DEFAULT_TEST_NETWORK, null, Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("time-same-day-not-covering", "time-wrap-not-covering");
    }

    @Test
    @DisplayName("startDate/endDate — if present — always wins over startTime/endTime, even when the time window covers now")
    void dateFields_alwaysWinOverTimeFields() throws Exception {
        seedTimeOfDay();
        // date-wins-over-time has a future startDate (2099) but a startTime/endTime window that covers
        // noon. If time were consulted it would read as "valid"; the date branch must win instead.
        List<Catalog> validTrue = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("tod", DEFAULT_TEST_NETWORK, null, Boolean.TRUE));
        assertThat(validTrue).extracting(Catalog::getId).doesNotContain("date-wins-over-time");

        List<Catalog> validFalse = pgQueryEngine.executeFilterQuery(
                jsonPathQuery("tod", DEFAULT_TEST_NETWORK, null, Boolean.FALSE));
        assertThat(validFalse).extracting(Catalog::getId).contains("date-wins-over-time");
    }
}
