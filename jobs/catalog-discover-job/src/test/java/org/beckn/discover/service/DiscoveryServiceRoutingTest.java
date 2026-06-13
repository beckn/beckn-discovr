package org.beckn.discover.service;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Resource;
import org.beckn.discover.service.elasticsearch.ElasticsearchQueryEngine;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.beckn.discover.service.postgresql.ProviderOfferEnricher;
import org.beckn.discover.service.response.CatalogPipeline;
import org.beckn.discover.service.response.ResponseProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the hotfix routing tree in {@link DiscoveryService}.
 *
 * Covers 7 combination happy paths, 7 empty-result paths, 7 boundary paths,
 * and 6 chain edge cases = 27 total tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscoveryServiceRoutingTest {

    @Mock private QueryEngine queryEngine;
    @Mock private TextSearchEngine textSearchEngine;
    @Mock private CatalogPipeline catalogPipeline;
    @Mock private ResponseProcessor responseProcessor;
    @Mock private ProviderOfferEnricher providerOfferEnricher;
    @Mock private DiscoveryMetrics metrics;
    @Mock private PostgreSQLQueryEngine pgQueryEngine;
    @Mock private ElasticsearchQueryEngine esQueryEngine;

    private DiscoveryProperties properties;
    private ExecutorService queryExecutor;

    // ── helpers ──────────────────────────────────────────────────────────────

    private DiscoveryService service(boolean withEs) {
        return new DiscoveryService(
                queryEngine, textSearchEngine, catalogPipeline,
                responseProcessor, providerOfferEnricher, metrics,
                properties, queryExecutor,
                withEs ? Optional.of(esQueryEngine) : Optional.empty(),
                pgQueryEngine);
    }

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);
        queryExecutor = Executors.newFixedThreadPool(4);
        when(metrics.getProcessingStats())
                .thenReturn(new DiscoveryMetrics.ProcessingStats(0, 0, 0, 0, 0.0));
    }

    // ── Standard stubs ────────────────────────────────────────────────────────

    private void stubPipelinePassthrough() {
        when(catalogPipeline.process(anyList(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private void stubBuildResponse() {
        when(responseProcessor.buildResponse(any(), any())).thenAnswer(inv -> {
            List<Catalog> cats = (List<Catalog>) inv.getArgument(0);
            Context ctx = inv.getArgument(1);
            return new DiscoverResponse(ctx, new DiscoverResponse.ResponseMessage(cats));
        });
    }

    private void stubBuildEmptyResponse() {
        when(responseProcessor.buildEmptyResponse(any())).thenAnswer(inv -> {
            Context ctx = inv.getArgument(0);
            return new DiscoverResponse(ctx, new DiscoverResponse.ResponseMessage(List.of()));
        });
    }

    private static Catalog buildCatalog(String id, int resourceCount) {
        var c = new Catalog();
        c.setId(id);
        var d = new Descriptor();
        d.setName("Cat " + id);
        c.setDescriptor(d);
        c.setOffers(List.of(Map.of("id", "offer-" + id)));
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < resourceCount; i++) {
            var r = new Resource();
            r.setId(id + "-res-" + i);
            resources.add(r);
        }
        c.setResources(resources);
        return c;
    }

    private static DiscoverRequest buildRequest(String filters, boolean hasSpatial, String text) {
        var ctx = new Context();
        ctx.setAction("discover");
        ctx.setMessageId(UUID.randomUUID().toString());
        ctx.setTransactionId(UUID.randomUUID().toString());

        var req = new DiscoverRequest();
        req.setContext(ctx);
        if (filters != null) req.setFilters(filters);
        if (hasSpatial) {
            var sc = new DiscoverRequest.SpatialConstraint();
            sc.setOperation("s_dwithin");
            sc.setDistanceMeters(1000.0);
            req.setSpatial(List.of(sc));
        }
        if (text != null) {
            req.setTextSearch(text);
        }
        return req;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HAPPY PATH — one per combination
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy paths — all 7 combinations")
    class HappyPaths {

        @Test
        @DisplayName("Case 1 (J): filter-only routes to PSQL executeFilterQuery")
        void case1_jsonPathOnly() throws Exception {
            var svc = service(false);
            var catalog = buildCatalog("cat-1", 3);
            when(queryEngine.executeFilterQuery(any())).thenReturn(List.of(catalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.name == 'x'", false, null));

            assertThat(resp.getCatalogs()).hasSize(1).extracting(Catalog::getId).containsExactly("cat-1");
            verify(queryEngine).executeFilterQuery(any());
            verify(metrics).incrementRouteSelected("B");
        }

        @Test
        @DisplayName("Case 2 (G): spatial-only routes to executeSpatialQuery")
        void case2_spatialOnly() throws Exception {
            var svc = service(false);
            var catalog = buildCatalog("cat-2", 2);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of(catalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest(null, true, null));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(queryEngine).executeSpatialQuery(any());
            verify(metrics).incrementRouteSelected("C");
        }

        @Test
        @DisplayName("Case 3 (T): text-only routes to textSearchEngine")
        void case3_textOnly() throws Exception {
            var svc = service(false);
            var catalog = buildCatalog("cat-3", 1);
            when(textSearchEngine.search(any(), any())).thenReturn(List.of(catalog));
            when(textSearchEngine.appliesSchemaFilter()).thenReturn(true);
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest(null, false, "coffee"));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(textSearchEngine).search(eq("coffee"), any());
            verify(metrics).incrementRouteSelected("D");
        }

        @Test
        @DisplayName("Case 4 (J+G): routes to pgQueryEngine.executeCombinedQuery (not queryEngine)")
        void case4_jsonPathAndSpatial() throws Exception {
            var svc = service(false);
            var catalog = buildCatalog("cat-4", 5);
            when(pgQueryEngine.executeCombinedQuery(any())).thenReturn(Optional.of(List.of(catalog)));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.price > 10", true, null));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(pgQueryEngine).executeCombinedQuery(any());
            verify(queryEngine, never()).executeCombinedQuery(any());
            verify(metrics).incrementRouteSelected("A");
        }

        @Test
        @DisplayName("Case 5 (G+T): spatial+text routes to executeSpatialQuery (engine handles text)")
        void case5_spatialAndText() throws Exception {
            var svc = service(false);
            var catalog = buildCatalog("cat-5", 3);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of(catalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            // G+T with no filters → path C
            var resp = svc.processDiscoveryRequest(buildRequest(null, true, "yoga"));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(queryEngine).executeSpatialQuery(any());
            verify(metrics).incrementRouteSelected("C");
        }

        @Test
        @DisplayName("Case 6 (J+T): chain — ES IDs then PSQL filter")
        void case6_jsonPathAndText() throws Exception {
            var svc = service(true);
            var catalog = buildCatalog("cat-6", 2);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt()))
                    .thenReturn(List.of("res-0", "res-1", "res-2"));
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(List.of(catalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.brand == 'Nike'", false, "running shoes"));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(esQueryEngine).fetchMatchingResourceIds(any(), anyInt());
            verify(pgQueryEngine).executeJsonPathQueryByResourceIds(any(), anyCollection());
            verify(metrics).incrementRouteSelected("chain");
        }

        @Test
        @DisplayName("Case 7 (J+G+T): chain — ES text+geo IDs then PSQL combined filter")
        void case7_jsonPathSpatialAndText() throws Exception {
            var svc = service(true);
            var catalog = buildCatalog("cat-7", 4);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt()))
                    .thenReturn(List.of("id-a", "id-b"));
            when(pgQueryEngine.executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(Optional.of(List.of(catalog)));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.rating >= 4", true, "hotel"));

            assertThat(resp.getCatalogs()).hasSize(1);
            verify(esQueryEngine).fetchMatchingResourceIds(any(), anyInt());
            verify(pgQueryEngine).executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection());
            verify(metrics).incrementRouteSelected("chain");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EMPTY RESULTS — one per combination
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty results — all 7 combinations")
    class EmptyResults {

        @Test
        @DisplayName("Case 1 (J) empty: returns empty response, no NPE")
        void case1_empty() throws Exception {
            var svc = service(false);
            when(queryEngine.executeFilterQuery(any())).thenReturn(List.of());
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest("$.x == 1", false, null));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
        }

        @Test
        @DisplayName("Case 2 (G) empty: returns empty response, no NPE")
        void case2_empty() throws Exception {
            var svc = service(false);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of());
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest(null, true, null));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
        }

        @Test
        @DisplayName("Case 3 (T) empty: returns empty response, no NPE")
        void case3_empty() throws Exception {
            var svc = service(false);
            when(textSearchEngine.search(any(), any())).thenReturn(List.of());
            when(textSearchEngine.appliesSchemaFilter()).thenReturn(true);
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest(null, false, "xyz"));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
        }

        @Test
        @DisplayName("Case 4 (J+G) empty: combined returns empty list, not Optional.empty")
        void case4_empty() throws Exception {
            var svc = service(false);
            when(pgQueryEngine.executeCombinedQuery(any())).thenReturn(Optional.of(List.of()));
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest("$.a", true, null));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
            // Must NOT fall back to parallel when Optional.of(empty)
            verify(queryEngine, never()).executeFilterQuery(any());
        }

        @Test
        @DisplayName("Case 5 (G+T) empty: returns empty response, no NPE")
        void case5_empty() throws Exception {
            var svc = service(false);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of());
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest(null, true, "gym"));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
        }

        @Test
        @DisplayName("Case 6 (J+T) empty-from-psql: text matches but PSQL has nothing")
        void case6_emptyFromPsql() throws Exception {
            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(List.of("r1", "r2"));
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection())).thenReturn(List.of());
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest("$.x > 999", false, "running"));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
        }

        @Test
        @DisplayName("Case 7 (J+G+T) empty: all three predicates match nothing")
        void case7_empty() throws Exception {
            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(List.of());
            stubBuildEmptyResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.x", true, "nothing"));

            assertThat(resp.getCatalogs()).isEmpty();
            // ES returned empty → CHAIN_EMPTY_FROM_ES fires, no PSQL call
            verify(pgQueryEngine, never()).executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection());
            verify(metrics).incrementChainEmptyResults();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BOUNDARY — exactly limit and limit+1 results
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Boundary — limit enforcement for all 7 combinations")
    class Boundary {

        // limit from properties.postgresql.resultLimit = 100 by default
        private static final int LIMIT = 100;

        private List<Catalog> buildCatalogList(int totalResources) {
            var cat = buildCatalog("c", totalResources);
            return List.of(cat);
        }

        @Test
        @DisplayName("Case 1 boundary: exactly LIMIT resources returned")
        void case1_atLimit() throws Exception {
            var svc = service(false);
            var cats = buildCatalogList(LIMIT);
            when(queryEngine.executeFilterQuery(any())).thenReturn(cats);
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.x", false, null));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 2 boundary: exactly LIMIT resources returned")
        void case2_atLimit() throws Exception {
            var svc = service(false);
            var cats = buildCatalogList(LIMIT);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(cats);
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest(null, true, null));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 3 boundary: LIMIT+1 results from ES, pipeline truncates to LIMIT")
        void case3_overLimit() throws Exception {
            var svc = service(false);
            var cats = buildCatalogList(LIMIT + 1);
            when(textSearchEngine.search(any(), any())).thenReturn(cats);
            when(textSearchEngine.appliesSchemaFilter()).thenReturn(true);
            // Simulate pipeline truncating to LIMIT
            when(catalogPipeline.process(anyList(), any(), anyBoolean()))
                    .thenAnswer(inv -> {
                        List<Catalog> c = inv.getArgument(0);
                        if (!c.isEmpty()) {
                            var truncated = buildCatalog("c", LIMIT);
                            return List.of(truncated);
                        }
                        return c;
                    });
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest(null, false, "tea"));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 4 boundary: exactly LIMIT resources returned from combined query")
        void case4_atLimit() throws Exception {
            var svc = service(false);
            var cats = buildCatalogList(LIMIT);
            when(pgQueryEngine.executeCombinedQuery(any())).thenReturn(Optional.of(cats));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.y", true, null));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 5 boundary: spatial+text returns LIMIT results")
        void case5_atLimit() throws Exception {
            var svc = service(false);
            var cats = buildCatalogList(LIMIT);
            when(queryEngine.executeSpatialQuery(any())).thenReturn(cats);
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest(null, true, "spa"));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 6 boundary: chain returns exactly LIMIT results")
        void case6_atLimit() throws Exception {
            var svc = service(true);
            List<String> ids = IntStream.range(0, LIMIT).mapToObj(i -> "r-" + i).toList();
            var cats = buildCatalogList(LIMIT);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(ids);
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection())).thenReturn(cats);
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.z", false, "limit-test"));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Case 7 boundary: chain+geo returns exactly LIMIT results")
        void case7_atLimit() throws Exception {
            var svc = service(true);
            List<String> ids = IntStream.range(0, LIMIT).mapToObj(i -> "r-" + i).toList();
            var cats = buildCatalogList(LIMIT);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(ids);
            when(pgQueryEngine.executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(Optional.of(cats));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.z", true, "limit-test"));
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(LIMIT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHAIN EDGE CASES
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chain edge cases")
    class ChainEdgeCases {

        @Test
        @DisplayName("Selective JSONPath + broad text: ES returns 500 IDs, PSQL matches 5")
        void selectiveJsonPath_broadText_psqlMatchesFew() throws Exception {
            var svc = service(true);
            List<String> bigCandidateList = IntStream.range(0, 500).mapToObj(i -> "res-" + i).toList();
            var matchedCatalog = buildCatalog("narrow", 5);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(bigCandidateList);
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(List.of(matchedCatalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.brand == 'Rare'", false, "generic text"));

            assertThat(resp.getCatalogs()).hasSize(1);
            assertThat(resp.getCatalogs().get(0).getResources()).hasSize(5);
            // 5 < LIMIT(100) → underreturn metric incremented
            verify(metrics).incrementChainUnderreturn();
            // ES candidate size recorded
            verify(metrics).recordEsResourceIdsCount(500);
        }

        @Test
        @DisplayName("Cap exhaustion: ES candidates capped at max-ids")
        void capExhaustion_esCappedAtMaxIds() throws Exception {
            // Set small cap so limit*overfetch > maxIds
            properties.getChain().setMaxIds(50);
            properties.getChain().setOverfetchFactor(10);
            properties.getPostgresql().setResultLimit(10);
            // limit=10, overfetch=10 → 100; maxIds=50 → cap fires

            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(List.of("r1"));
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(List.of(buildCatalog("c", 1)));
            stubPipelinePassthrough();
            stubBuildResponse();

            var req = buildRequest("$.x", false, "test");
            svc.processDiscoveryRequest(req);

            // ES was called with size=50 (the cap), not 100
            ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(esQueryEngine).fetchMatchingResourceIds(any(), sizeCaptor.capture());
            assertThat(sizeCaptor.getValue()).isEqualTo(50);
            // Truncated-by-cap metric incremented
            verify(metrics).incrementChainTruncatedByCap();
        }

        @Test
        @DisplayName("Empty ES candidates (case 6): text matches nothing → short-circuit empty")
        void emptyEsCandidates_shortCircuit() throws Exception {
            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt())).thenReturn(List.of());
            stubBuildEmptyResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.x", false, "impossible query"));

            assertThat(resp.getCatalogs()).isEmpty();
            // PSQL must not be called when ES returns empty
            verify(pgQueryEngine, never()).executeJsonPathQueryByResourceIds(any(), anyCollection());
            // chain-empty-results metric incremented
            verify(metrics).incrementChainEmptyResults();
        }

        @Test
        @DisplayName("Empty PSQL after IN-filter (case 6): IDs pass ES but none pass JSONPath")
        void emptyPsqlAfterInFilter() throws Exception {
            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt()))
                    .thenReturn(List.of("id-1", "id-2", "id-3"));
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(List.of());
            stubBuildEmptyResponse();
            when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of());

            var resp = svc.processDiscoveryRequest(buildRequest("$.strictField == 'impossible'", false, "text"));

            assertThat(resp.getCatalogs()).isEmpty();
            verify(responseProcessor).buildEmptyResponse(any());
            verify(metrics).incrementChainEmptyResults();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADDITIONAL CHAIN EDGE CASES
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Additional chain edge cases")
    class AdditionalChainEdgeCases {

        @Test
        @DisplayName("Case 7 spatial fallback: when PSQL spatial conditions cannot be built, falls back to case-6 style")
        void case7_spatialFallback_toCase6() throws Exception {
            var svc = service(true);
            when(esQueryEngine.fetchMatchingResourceIds(any(), anyInt()))
                    .thenReturn(List.of("id-x", "id-y"));
            // Spatial build fails → Optional.empty()
            when(pgQueryEngine.executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(Optional.empty());
            // Case-6 fallback returns results
            var catalog = buildCatalog("fallback", 3);
            when(pgQueryEngine.executeJsonPathQueryByResourceIds(any(), anyCollection()))
                    .thenReturn(List.of(catalog));
            stubPipelinePassthrough();
            stubBuildResponse();

            var resp = svc.processDiscoveryRequest(buildRequest("$.x", true, "hotel near me"));

            assertThat(resp.getCatalogs()).hasSize(1);
            // Both spatial and non-spatial allowlist paths must have been called
            verify(pgQueryEngine).executeJsonPathAndSpatialQueryByResourceIds(any(), anyCollection());
            verify(pgQueryEngine).executeJsonPathQueryByResourceIds(any(), anyCollection());
            verify(metrics).incrementRouteSelected("chain");
        }

        @Test
        @DisplayName("Case 4 (J+G): throws IllegalStateException when spatial conditions cannot be built")
        void case4_throwsWhenSpatialConditionsCannotBeBuilt() throws Exception {
            var svc = service(false);
            when(pgQueryEngine.executeCombinedQuery(any())).thenReturn(Optional.empty());

            var req = buildRequest("$.price > 5", true, null);

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> svc.processDiscoveryRequest(req));

            // Verify no empty-response was built — the exception path was taken
            verify(responseProcessor, never()).buildEmptyResponse(any());
        }
    }
}
