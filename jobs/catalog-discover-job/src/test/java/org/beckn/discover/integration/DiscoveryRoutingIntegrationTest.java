package org.beckn.discover.integration;

import org.assertj.core.api.Assertions;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Resource;
import org.beckn.discover.service.DiscoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
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
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

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
