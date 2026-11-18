package org.beckn.discover.integration;

import org.assertj.core.api.Assertions;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Resource;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * End-to-end integration tests for the 7 J/G/T query routing combinations.
 *
 * <p><b>Test scope and coverage strategy</b> — the 7 routing cases are exercised by
 * three complementary test classes; this class is the PostgreSQL/PostGIS-backed
 * layer:</p>
 *
 * <table>
 *   <caption>Routing coverage</caption>
 *   <tr><th>Case</th><th>J</th><th>G</th><th>T</th><th>End-to-end coverage</th></tr>
 *   <tr><td>1</td><td>&#x2713;</td><td></td><td></td>
 *       <td><b>this class</b> — PSQL JSONPath</td></tr>
 *   <tr><td>2</td><td></td><td>&#x2713;</td><td></td>
 *       <td><b>this class</b> — PostGIS spatial</td></tr>
 *   <tr><td>3</td><td></td><td></td><td>&#x2713;</td>
 *       <td>{@code ElasticsearchTextSearchEngineIntegrationTest} — semantic / BM25 ES</td></tr>
 *   <tr><td>4</td><td>&#x2713;</td><td>&#x2713;</td><td></td>
 *       <td><b>this class</b> — combined PSQL+PostGIS</td></tr>
 *   <tr><td>5</td><td></td><td>&#x2713;</td><td>&#x2713;</td>
 *       <td>{@code ElasticsearchQueryEngineGeoFilterTest} (G+T ES query);
 *           <b>this class</b> verifies graceful degradation when ES is absent</td></tr>
 *   <tr><td>6</td><td>&#x2713;</td><td></td><td>&#x2713;</td>
 *       <td>{@code DiscoveryServiceRoutingTest} (chain unit); <b>this class</b>
 *           verifies graceful degradation to case 1 when ES is absent</td></tr>
 *   <tr><td>7</td><td>&#x2713;</td><td>&#x2713;</td><td>&#x2713;</td>
 *       <td>{@code DiscoveryServiceRoutingTest} (chain unit); <b>this class</b>
 *           verifies graceful degradation to case 4 when ES is absent</td></tr>
 * </table>
 *
 * <p>The {@link BaseIntegrationTest} profile sets {@code discovery.spatial.engine}
 * to its default ({@code postgresql}), so the {@code ElasticsearchQueryEngine}
 * bean is not loaded. That deliberately exercises the ES-absent fallback paths
 * for cases 5/6/7 — i.e. the routing tree's degradation contract is part of the
 * shipped behaviour and must be verified here.</p>
 *
 * <p>The ES-backed cases (3 semantic/BM25, 5 ES geo+text, 6/7 ES&#x2192;PSQL chain)
 * are exercised by:</p>
 * <ul>
 *   <li>{@code DiscoveryServiceRoutingTest} — covers all 7 routing decisions with
 *       Mockito, including the full chain pipeline (ES&#x2192;PSQL ID lookup).</li>
 *   <li>{@code ElasticsearchTextSearchEngineIntegrationTest} — exercises
 *       {@code ElasticsearchTextSearchEngine.search} against a live ES container.</li>
 *   <li>{@code ElasticsearchQueryEngineGeoFilterTest} — exercises
 *       {@code ElasticsearchQueryEngine.executeSpatialQuery} G+T path.</li>
 * </ul>
 */
class DiscoveryRoutingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DiscoveryService discoveryService;

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 1 — J (JSONPath only) — PostgreSQL JSONPath path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 1 — J (JSONPath only)")
    class JsonPathOnly {

        @Test
        @DisplayName("Routes through PostgreSQL JSONPath and returns matching catalog")
        void jsonPathOnlyReturnsMatchingCatalog() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs()).isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }

        @Test
        @DisplayName("JSONPath with no match returns empty catalogs")
        void jsonPathOnlyNoMatchReturnsEmpty() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"DOES_NOT_EXIST\")");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            Assertions.assertThat(response.getMessage().getCatalogs()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 2 — G (Spatial only) — PostGIS path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 2 — G (Spatial only)")
    class SpatialOnly {

        @Test
        @DisplayName("Routes through PostGIS spatial query and returns catalog in radius")
        void spatialOnlyReturnsCatalogInRadius() {
            DiscoverRequest request = newRequest();
            request.setSpatial(List.of(spatialDwithin(77.5946, 12.9716, 1000.0)));

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs()).isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }

        @Test
        @DisplayName("Spatial radius outside any geometry returns empty")
        void spatialOnlyOutsideReturnsEmpty() {
            DiscoverRequest request = newRequest();
            // Far away point in the Atlantic — no fixtures there.
            request.setSpatial(List.of(spatialDwithin(-30.0, 0.0, 1000.0)));

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            Assertions.assertThat(response.getMessage().getCatalogs()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 4 — J+G (JSONPath + spatial) — combined PSQL+PostGIS path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 4 — J+G (JSONPath + spatial combined)")
    class JsonPathAndSpatial {

        @Test
        @DisplayName("Routes through PostgreSQL combined query — both predicates applied")
        void combinedJsonPathAndSpatialReturnsCatalog() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");
            request.setSpatial(List.of(spatialDwithin(77.5946, 12.9716, 1500.0)));

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs()).isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }

        @Test
        @DisplayName("JSONPath matches but spatial radius excludes — returns empty")
        void combinedJsonPathMatchesButSpatialExcludesReturnsEmpty() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");
            request.setSpatial(List.of(spatialDwithin(-30.0, 0.0, 100.0)));

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            Assertions.assertThat(response.getMessage().getCatalogs()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 5 — G+T (Spatial + text) — ES-absent fallback to PSQL spatial-only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 5 — G+T (Spatial + text) without ES bean — fallback")
    class SpatialAndTextWithoutEs {

        /**
         * Routing for case 5 calls {@code queryEngine.executeSpatialQuery}. In the test
         * profile {@code discovery.spatial.engine=postgresql} (default), so the @Primary
         * QueryEngine is PostgreSQL — the spatial part is honoured and the text query
         * is ignored. ES-backed G+T is covered by {@code ElasticsearchQueryEngineGeoFilterTest}.
         */
        @Test
        @DisplayName("Falls back to PSQL spatial when ES bean absent — no crash")
        void spatialAndTextWithoutEsDoesNotCrash() {
            DiscoverRequest request = newRequest();
            request.setSpatial(List.of(spatialDwithin(77.5946, 12.9716, 1500.0)));
            request.setTextSearch("ev charging stations");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs())
                    .as("Spatial part must still produce a catalog even when ES bean is absent")
                    .isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 6 — J+T (JSONPath + text) — ES-absent fallback to J only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 6 — J+T without ES bean — fallback to J only")
    class JsonPathAndTextWithoutEs {

        /**
         * When the ES engine bean is absent the JSONPath+text route drops the text
         * condition and re-routes to the JSONPath-only path. ES-backed chain is
         * covered by the unit tests in {@code DiscoveryServiceRoutingTest}.
         */
        @Test
        @DisplayName("Drops text, runs JSONPath only — returns JSONPath result")
        void jsonPathAndTextWithoutEsFallsBackToJsonPathOnly() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");
            request.setTextSearch("ev charging");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs())
                    .as("JSONPath-only fallback must still produce a catalog")
                    .isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Case 7 — J+G+T — ES-absent fallback to J+G
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Case 7 — J+G+T without ES bean — fallback to J+G")
    class JsonPathAndSpatialAndTextWithoutEs {

        @Test
        @DisplayName("Drops text, runs JSONPath + spatial combined query")
        void jsonPathSpatialTextWithoutEsFallsBackToCombinedQuery() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");
            request.setSpatial(List.of(spatialDwithin(77.5946, 12.9716, 1500.0)));
            request.setTextSearch("ev charging");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            assertDiscoverResponseValid(response, request.getContext());
            Assertions.assertThat(response.getMessage().getCatalogs())
                    .as("J+G fallback must still produce a catalog")
                    .isNotEmpty();
            assertResourceIdsContain(response, "ev-charger-ccs2-001");
        }

        @Test
        @DisplayName("Drops text, JSONPath matches but spatial excludes — returns empty")
        void jsonPathMatchSpatialExcludeWithoutEsReturnsEmpty() {
            DiscoverRequest request = newRequest();
            request.setFilters(
                    "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")");
            request.setSpatial(List.of(spatialDwithin(-30.0, 0.0, 100.0)));
            request.setTextSearch("ev charging");

            DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

            Assertions.assertThat(response.getMessage().getCatalogs()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  RFC 9535 filters on every PostgreSQL route — J, J+G, J+G+T
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Fixture: ev-charger-ccs2-001 (CCS2, 60 kW, at 77.5946/12.9716), ev-charger-ccs2-002 (CCS2,
    // 120 kW, ~2.8 km away at 77.57/12.98, provider greencharge-koramangala), ev-charger-type2-001
    // (Type2, no location row). Radius 5 km around ccs2-001 holds both CCS2 chargers; 1.5 km only 001.

    private static final String RFC_CCS2 =
            "$.catalogs[*].resources[?(@.resourceAttributes.connectorType == 'CCS2')]";
    private static final String RFC_CCS2_100KW = "$.catalogs[*].resources[?(@.resourceAttributes.connectorType"
            + " == 'CCS2' && @.resourceAttributes.maxPowerKW >= 100)]";
    private static final String LEGACY_CCS2 =
            "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")";
    private static final String RFC_OFFER_60KW = "$.catalogs[*].offers[?(@.id == 'offer-ccs2-60kw-kwh')]";
    private static final String CCS2_001 = "ev-charger-ccs2-001";
    private static final String CCS2_002 = "ev-charger-ccs2-002";

    @Autowired
    private PostgreSQLQueryEngine pgQueryEngine;

    @Nested
    @DisplayName("RFC 9535 filter — identical results with and without a spatial constraint")
    class Rfc9535FilterOnEveryRoute {

        @Test
        @DisplayName("J+G: RFC 9535 filter and radius both applied (regression: was HTTP 500)")
        void jsonPathAndSpatial_rfc9535_bothPredicatesApplied() {
            Assertions.assertThat(resourceIds(discover(RFC_CCS2_100KW, 5000.0, null))).containsExactly(CCS2_002);
            Assertions.assertThat(resourceIds(discover(RFC_CCS2_100KW, 1500.0, null))).isEmpty();
            Assertions.assertThat(resourceIds(discover(RFC_CCS2, 1500.0, null))).containsExactly(CCS2_001);
        }

        @Test
        @DisplayName("J+G: RFC 9535 and legacy Postgres-native forms of the same filter return the same resources")
        void jsonPathAndSpatial_rfc9535MatchesLegacyForm() {
            for (double radius : new double[] {1500.0, 5000.0}) {
                Assertions.assertThat(resourceIds(discover(RFC_CCS2, radius, null)))
                        .as("radius %s m", radius)
                        .isEqualTo(resourceIds(discover(LEGACY_CCS2, radius, null)));
            }
            Assertions.assertThat(resourceIds(discover(LEGACY_CCS2, 5000.0, null)))
                    .containsExactly(CCS2_001, CCS2_002);
        }

        @Test
        @DisplayName("J and J+G: an RFC 9535 offer filter narrows offers the same way with and without spatial")
        void rfc9535OfferFilter_narrowsOffersOnBothRoutes() {
            for (DiscoverResponse response : List.of(
                    discover(RFC_OFFER_60KW, null, null), discover(RFC_OFFER_60KW, 5000.0, null))) {
                Assertions.assertThat(resourceIds(response)).containsExactly(CCS2_001);
                Assertions.assertThat(offerIds(response)).containsExactly("offer-ccs2-60kw-kwh");
            }
        }

        @Test
        @DisplayName("J+G+T without ES (PostgreSQL engine): text dropped, RFC 9535 filter + radius applied")
        void jsonPathSpatialText_withoutEs_rfc9535() {
            Assertions.assertThat(resourceIds(discover(RFC_CCS2_100KW, 5000.0, "ev charging")))
                    .containsExactly(CCS2_002);
        }

        @Test
        @DisplayName("J+G+T chain step 2 (ES engine): RFC 9535 filter + radius on the ES id allowlist")
        void jsonPathSpatialText_chainStep2_rfc9535() throws Exception {
            List<String> allIds = List.of(CCS2_001, CCS2_002, "ev-charger-type2-001");
            QueryRequest qr = QueryRequest.from(request(RFC_CCS2_100KW, 5000.0, "ev charging"));

            List<Catalog> catalogs = pgQueryEngine.executeJsonPathAndSpatialQueryByResourceIds(qr, allIds)
                    .orElseThrow();
            Assertions.assertThat(resourceIds(catalogs)).containsExactly(CCS2_002);

            List<Catalog> notInAllowlist = pgQueryEngine
                    .executeJsonPathAndSpatialQueryByResourceIds(qr, List.of(CCS2_001)).orElseThrow();
            Assertions.assertThat(resourceIds(notInAllowlist)).isEmpty();
        }

        @Test
        @DisplayName("chained RFC 9535 filters (catalogs[?()].resources[?()]) on J and J+G")
        void chainedFilters_onJsonPathAndCombinedRoutes() {
            String chained = "$.catalogs[?(@.id == 'catalog-ev-charging-001')]"
                    + ".resources[?(@.resourceAttributes.maxPowerKW >= 100)]";
            Assertions.assertThat(resourceIds(discover(chained, null, null))).containsExactly(CCS2_002);
            Assertions.assertThat(resourceIds(discover(chained, 5000.0, null))).containsExactly(CCS2_002);
            Assertions.assertThat(resourceIds(discover(chained, 1500.0, null))).isEmpty();
        }

        @Test
        @DisplayName("filter selector after [*] / at the root follows RFC 9535 (member values), "
                + "never a SQL error, on J and J+G")
        void filterOverObjectMembers_rfc9535Semantics() {
            // [*][?()] filters the member values of each resource object. No member value has a
            // resourceAttributes member, so nothing matches — a 500 before this fix.
            String overMembers = "$.catalogs[*].resources[*][?(@.resourceAttributes.connectorType == 'CCS2')]";
            Assertions.assertThat(resourceIds(discover(overMembers, null, null))).isEmpty();
            Assertions.assertThat(resourceIds(discover(overMembers, 5000.0, null))).isEmpty();

            // ...but a member value that does satisfy the filter matches: ccs2-002's "provider" member.
            String providerMember = "$.catalogs[*].resources[*][?(@.id == 'greencharge-koramangala')]";
            Assertions.assertThat(resourceIds(discover(providerMember, null, null))).containsExactly(CCS2_002);
            Assertions.assertThat(resourceIds(discover(providerMember, 5000.0, null))).containsExactly(CCS2_002);

            // $[?()] — the LTS Intent.filters example shape — iterates the root object's members.
            Assertions.assertThat(resourceIds(discover(
                    "$[?(@.resourceAttributes.connectorType == 'CCS2')]", null, null))).isEmpty();
        }

        private DiscoverResponse discover(String filter, Double radiusMeters, String text) {
            DiscoverResponse response = discoveryService.processDiscoveryRequest(request(filter, radiusMeters, text));
            Assertions.assertThat(response.getMessage()).isNotNull();
            return response;
        }

        private DiscoverRequest request(String filter, Double radiusMeters, String text) {
            DiscoverRequest request = newRequest();
            request.setFilters(filter);
            if (radiusMeters != null) {
                request.setSpatial(List.of(spatialDwithin(77.5946, 12.9716, radiusMeters)));
            }
            if (text != null) {
                request.setTextSearch(text);
            }
            return request;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static List<String> resourceIds(DiscoverResponse response) {
        return resourceIds(response.getMessage().getCatalogs());
    }

    private static List<String> resourceIds(List<Catalog> catalogs) {
        return catalogs.stream()
                .flatMap(c -> c.getResources() == null ? java.util.stream.Stream.empty() : c.getResources().stream())
                .map(Resource::getId)
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> offerIds(DiscoverResponse response) {
        return response.getMessage().getCatalogs().stream()
                .flatMap(c -> c.getOffers() == null ? java.util.stream.Stream.empty() : c.getOffers().stream())
                .map(o -> OBJECT_MAPPER.valueToTree(o).path("id").asText(null))
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
    }

    private DiscoverRequest newRequest() {
        Context context = buildContext(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        DiscoverRequest request = new DiscoverRequest(context);
        DiscoverRequest.Message message = new DiscoverRequest.Message();
        message.setIntent(new DiscoverRequest.Intent());
        request.setMessage(message);
        return request;
    }

    private DiscoverRequest.SpatialConstraint spatialDwithin(double lon, double lat, double radiusMeters) {
        DiscoverRequest.SpatialConstraint sc = new DiscoverRequest.SpatialConstraint();
        sc.setOperation("s_dwithin");
        sc.setTargets("$.catalogs[*].resources[*].availableAt[*].geo");
        DiscoverRequest.GeoJSONGeometry geometry = new DiscoverRequest.GeoJSONGeometry();
        geometry.setType("Point");
        geometry.setCoordinates(List.of(lon, lat));
        sc.setGeometry(geometry);
        sc.setDistanceMeters(radiusMeters);
        return sc;
    }

    private void assertResourceIdsContain(DiscoverResponse response, String... expectedIds) {
        List<String> actualResourceIds = response.getMessage().getCatalogs().stream()
                .flatMap(c -> c.getResources().stream())
                .map(Resource::getId)
                .collect(Collectors.toList());
        Assertions.assertThat(actualResourceIds).contains(expectedIds);
    }
}
