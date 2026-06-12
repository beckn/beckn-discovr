package org.beckn.discover.service;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.beckn.discover.service.postgresql.ProviderOfferEnricher;
import org.beckn.discover.service.response.CatalogPipeline;
import org.beckn.discover.service.response.ResponseProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.beckn.discover.model.DiscoverRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock private QueryEngine queryEngine;
    @Mock private TextSearchEngine textSearchEngine;
    @Mock private CatalogPipeline catalogPipeline;
    @Mock private ResponseProcessor responseProcessor;
    @Mock private ProviderOfferEnricher providerOfferEnricher;
    @Mock private DiscoveryMetrics metrics;

    private DiscoveryProperties properties;
    private ExecutorService queryExecutor;
    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        queryExecutor = Executors.newSingleThreadExecutor();

        // Stub metrics to avoid NPE on LatencyTracker log call
        when(metrics.getProcessingStats())
                .thenReturn(new DiscoveryMetrics.ProcessingStats(0, 0, 0, 0, 0.0));

        discoveryService = new DiscoveryService(
                queryEngine, textSearchEngine, catalogPipeline,
                responseProcessor, providerOfferEnricher, metrics,
                properties, queryExecutor);
    }

    @Test
    void requireOffersEnabled_discardsCatalogsWithNoOffers() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(true);

        var catalogWithOffers = buildCatalog("cat-1", List.of(Map.of("id", "offer-1")));
        var catalogNoOffers = buildCatalog("cat-2", null);
        var catalogEmptyOffers = buildCatalog("cat-3", new ArrayList<>());
        var processed = new ArrayList<>(List.of(catalogWithOffers, catalogNoOffers, catalogEmptyOffers));

        var request = buildTextSearchRequest("find charging");

        when(textSearchEngine.search(any(), any())).thenReturn(List.of(catalogWithOffers, catalogNoOffers, catalogEmptyOffers));
        when(textSearchEngine.appliesSchemaFilter()).thenReturn(false);
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(processed);
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv -> {
            List<Catalog> catalogs = inv.getArgument(0);
            Context ctx = inv.getArgument(1);
            var msg = new DiscoverResponse.ResponseMessage(catalogs);
            return new DiscoverResponse(ctx, msg);
        });

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response.getCatalogs())
                .as("Only catalog with offers should remain")
                .hasSize(1)
                .extracting(Catalog::getId)
                .containsExactly("cat-1");
    }

    @Test
    void requireOffersDisabled_keepsCatalogsWithNoOffers() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);

        var catalogWithOffers = buildCatalog("cat-1", List.of(Map.of("id", "offer-1")));
        var catalogNoOffers = buildCatalog("cat-2", null);
        var processed = new ArrayList<>(List.of(catalogWithOffers, catalogNoOffers));

        var request = buildTextSearchRequest("find charging");

        when(textSearchEngine.search(any(), any())).thenReturn(List.of(catalogWithOffers, catalogNoOffers));
        when(textSearchEngine.appliesSchemaFilter()).thenReturn(false);
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(processed);
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv -> {
            List<Catalog> catalogs = inv.getArgument(0);
            Context ctx = inv.getArgument(1);
            var msg = new DiscoverResponse.ResponseMessage(catalogs);
            return new DiscoverResponse(ctx, msg);
        });

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response.getCatalogs())
                .as("Both catalogs should remain when require-offers is disabled")
                .hasSize(2)
                .extracting(Catalog::getId)
                .containsExactly("cat-1", "cat-2");
    }

    @Test
    void requireOffersEnabled_allDiscarded_returnsEmptyResponse() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(true);

        var catalogNoOffers = buildCatalog("cat-1", null);
        var processed = new ArrayList<>(List.of(catalogNoOffers));

        var request = buildTextSearchRequest("find charging");
        var emptyResponse = new DiscoverResponse(request.getContext(),
                new DiscoverResponse.ResponseMessage(List.of()));

        when(textSearchEngine.search(any(), any())).thenReturn(List.of(catalogNoOffers));
        when(textSearchEngine.appliesSchemaFilter()).thenReturn(false);
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(processed);
        when(responseProcessor.buildEmptyResponse(any())).thenReturn(emptyResponse);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(request);

        assertThat(response.getCatalogs()).isEmpty();
        verify(responseProcessor).buildEmptyResponse(any());
    }

    // ── C5: Path B timeout tests ──────────────────────────────────────────────

    @Test
    void pathB_slowFilterQuery_throwsAfterTimeout() throws Exception {
        // GIVEN: timeout of 1 second, filter query that hangs for 10 seconds
        properties.getPostgresql().setParallelQueryTimeoutSeconds(1);

        CountDownLatch blocker = new CountDownLatch(1);
        when(queryEngine.executeFilterQuery(any())).thenAnswer(inv -> {
            blocker.await(); // hangs until test completes
            return List.of();
        });

        var request = buildFilterOnlyRequest("{\"name\":\"slow\"}");

        // WHEN / THEN: should throw within ~2 seconds (generous upper bound)
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> discoveryService.processDiscoveryRequest(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process discovery request");
        long elapsed = System.currentTimeMillis() - start;

        blocker.countDown(); // release the blocked query thread
        // Verify timeout fired well before 5 seconds
        assertThat(elapsed).isLessThan(5000);
    }

    @Test
    void pathC_slowSpatialQuery_throwsAfterTimeout() throws Exception {
        // GIVEN: timeout of 1 second, spatial query that hangs for 10 seconds
        properties.getPostgresql().setParallelQueryTimeoutSeconds(1);

        CountDownLatch blocker = new CountDownLatch(1);
        when(queryEngine.executeSpatialQuery(any())).thenAnswer(inv -> {
            blocker.await(); // hangs until test completes
            return List.of();
        });

        var request = buildSpatialOnlyRequest();

        // WHEN / THEN
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> discoveryService.processDiscoveryRequest(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process discovery request");
        long elapsed = System.currentTimeMillis() - start;

        blocker.countDown();
        assertThat(elapsed).isLessThan(5000);
    }

    @Test
    void pathB_fastFilterQuery_returnsNormally() throws Exception {
        properties.getPostgresql().setParallelQueryTimeoutSeconds(10);
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);

        var catalog = buildCatalog("cat-1", null);
        when(queryEngine.executeFilterQuery(any())).thenReturn(List.of(catalog));
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenReturn(List.of(catalog));
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv -> {
            List<Catalog> cats = inv.getArgument(0);
            return new DiscoverResponse(inv.getArgument(1), new DiscoverResponse.ResponseMessage(cats));
        });

        DiscoverResponse response = discoveryService.processDiscoveryRequest(buildFilterOnlyRequest("{\"name\":\"fast\"}"));

        assertThat(response.getCatalogs()).hasSize(1);
    }

    // ── F-6: text predicate applied (AND-intersected) on the JSONPath branch ──

    @Test
    void textPlusFilters_intersectsTextWithFilterResult_textNotDropped() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);

        // Filter (PG/JSONPath) branch returns R1 + R2; text (ES) branch matches only R1.
        Catalog filterCatalog = buildCatalogWithResources("cat-1", "R1", "R2");
        Catalog textCatalog   = buildCatalogWithResources("cat-1", "R1");

        when(queryEngine.executeFilterQuery(any())).thenReturn(List.of(filterCatalog));
        when(textSearchEngine.search(any(), any())).thenReturn(List.of(textCatalog));
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv ->
                new DiscoverResponse(inv.getArgument(1), new DiscoverResponse.ResponseMessage(inv.getArgument(0))));

        DiscoverResponse response = discoveryService.processDiscoveryRequest(
                buildFilterAndTextRequest("{\"name\":\"coffee\"}", "coffee"));

        // F-6: text WAS applied (not dropped) — only R1, present in BOTH branches, survives.
        verify(textSearchEngine).search(any(), any());
        assertThat(response.getCatalogs()).hasSize(1);
        assertThat(response.getCatalogs().get(0).getResources())
                .extracting(org.beckn.discover.model.Resource::getId)
                .as("AND of jsonpath {R1,R2} and text {R1} must be {R1}")
                .containsExactly("R1");
    }

    @Test
    void textPlusFilters_noTextMatch_yieldsEmptyAnd() throws Exception {
        Catalog filterCatalog = buildCatalogWithResources("cat-1", "R1", "R2");
        Catalog textCatalog   = buildCatalogWithResources("cat-9", "R9"); // disjoint ids

        var emptyResponse = new DiscoverResponse(null, new DiscoverResponse.ResponseMessage(List.of()));
        when(queryEngine.executeFilterQuery(any())).thenReturn(List.of(filterCatalog));
        when(textSearchEngine.search(any(), any())).thenReturn(List.of(textCatalog));
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(responseProcessor.buildEmptyResponse(any())).thenReturn(emptyResponse);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(
                buildFilterAndTextRequest("{\"name\":\"coffee\"}", "tea"));

        verify(textSearchEngine).search(any(), any());
        assertThat(response.getCatalogs()).isEmpty();
    }

    @Test
    void textPlusFilters_filterEmpty_skipsTextQuery() throws Exception {
        var emptyResponse = new DiscoverResponse(null, new DiscoverResponse.ResponseMessage(List.of()));
        when(queryEngine.executeFilterQuery(any())).thenReturn(List.of());
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(responseProcessor.buildEmptyResponse(any())).thenReturn(emptyResponse);

        DiscoverResponse response = discoveryService.processDiscoveryRequest(
                buildFilterAndTextRequest("{\"name\":\"none\"}", "none"));

        // Structured branch empty → intersection is empty → no point querying text.
        verify(textSearchEngine, org.mockito.Mockito.never()).search(any(), any());
        assertThat(response.getCatalogs()).isEmpty();
    }

    // ── F-6: text rides the spatial leg (existing path reused, no separate text query) ──

    @Test
    void textPlusSpatial_foldsTextIntoSpatialQuery_noSeparateTextQuery() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);
        Catalog spatialCatalog = buildCatalogWithResources("cat-1", "R1");

        when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of(spatialCatalog));
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv ->
                new DiscoverResponse(inv.getArgument(1), new DiscoverResponse.ResponseMessage(inv.getArgument(0))));

        DiscoverResponse response = discoveryService.processDiscoveryRequest(buildSpatialAndTextRequest("coffee"));

        // text+spatial reuses the ES spatial query (which folds text in) — no separate text query.
        verify(queryEngine).executeSpatialQuery(any());
        verify(textSearchEngine, org.mockito.Mockito.never()).search(any(), any());
        assertThat(response.getCatalogs()).hasSize(1);
    }

    @Test
    void allThree_textRidesSpatialLeg_noSeparateTextQuery() throws Exception {
        properties.getFilter().setDiscardCatalogsWithoutOffers(false);
        Catalog filterCatalog  = buildCatalogWithResources("cat-1", "R1", "R2"); // jsonpath
        Catalog spatialCatalog = buildCatalogWithResources("cat-1", "R1");        // spatial ∧ text

        when(queryEngine.executeCombinedQuery(any())).thenReturn(Optional.empty()); // → parallel fallback
        when(queryEngine.executeFilterQuery(any())).thenReturn(List.of(filterCatalog));
        when(queryEngine.executeSpatialQuery(any())).thenReturn(List.of(spatialCatalog));
        when(catalogPipeline.process(anyList(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(responseProcessor.buildResponse(anyList(), any())).thenAnswer(inv ->
                new DiscoverResponse(inv.getArgument(1), new DiscoverResponse.ResponseMessage(inv.getArgument(0))));

        DiscoverResponse response = discoveryService.processDiscoveryRequest(
                buildAllThreeRequest("{\"name\":\"x\"}", "coffee"));

        // Triple reuses the combined/parallel path; text rides the ES spatial leg, no separate text query.
        verify(queryEngine).executeSpatialQuery(any());
        verify(queryEngine).executeFilterQuery(any());
        verify(textSearchEngine, org.mockito.Mockito.never()).search(any(), any());
        // jsonpath {R1,R2} ∩ (spatial ∧ text) {R1} = {R1}
        assertThat(response.getCatalogs().get(0).getResources())
                .extracting(org.beckn.discover.model.Resource::getId)
                .containsExactly("R1");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DiscoverRequest buildFilterOnlyRequest(String filters) {
        var context = new Context();
        context.setAction("discover");
        context.setMessageId(UUID.randomUUID().toString());
        context.setTransactionId(UUID.randomUUID().toString());

        var request = new DiscoverRequest();
        request.setContext(context);
        request.setFilters(filters);
        return request;
    }

    private static DiscoverRequest buildSpatialOnlyRequest() {
        var context = new Context();
        context.setAction("discover");
        context.setMessageId(UUID.randomUUID().toString());
        context.setTransactionId(UUID.randomUUID().toString());

        var constraint = new DiscoverRequest.SpatialConstraint();
        constraint.setOperation("s_dwithin");
        constraint.setDistanceMeters(5000.0);

        var request = new DiscoverRequest();
        request.setContext(context);
        request.setSpatial(List.of(constraint));
        return request;
    }

    private static Catalog buildCatalog(String id, List<Object> offers) {
        var catalog = new Catalog();
        catalog.setId(id);
        var descriptor = new Descriptor();
        descriptor.setName("Test Catalog " + id);
        catalog.setDescriptor(descriptor);
        catalog.setOffers(offers);
        catalog.setResources(new ArrayList<>());
        return catalog;
    }

    private static DiscoverRequest buildTextSearchRequest(String query) {
        var context = new Context();
        context.setAction("discover");
        context.setMessageId(UUID.randomUUID().toString());
        context.setTransactionId(UUID.randomUUID().toString());
        var intent = new DiscoverRequest.Intent();
        intent.setTextSearch(query);

        var message = new DiscoverRequest.Message();
        message.setIntent(intent);

        var request = new DiscoverRequest();
        request.setContext(context);
        request.setMessage(message);
        return request;
    }

    /** Request carrying spatial + text (routes to the ES spatial query which folds text in). */
    private static DiscoverRequest buildSpatialAndTextRequest(String text) {
        var request = buildSpatialOnlyRequest();
        request.setTextSearch(text);
        return request;
    }

    /** Request carrying jsonpath + spatial + text (combined/parallel path; text on spatial leg). */
    private static DiscoverRequest buildAllThreeRequest(String filters, String text) {
        var request = buildSpatialOnlyRequest();
        request.setFilters(filters);
        request.setTextSearch(text);
        return request;
    }

    /** Request carrying BOTH a JSONPath filter and a text-search term (F-6 path). */
    private static DiscoverRequest buildFilterAndTextRequest(String filters, String text) {
        var context = new Context();
        context.setAction("discover");
        context.setMessageId(UUID.randomUUID().toString());
        context.setTransactionId(UUID.randomUUID().toString());

        var request = new DiscoverRequest();
        request.setContext(context);
        request.setFilters(filters);   // populates message.intent.filters
        request.setTextSearch(text);   // populates message.intent.textSearch
        return request;
    }

    private static Catalog buildCatalogWithResources(String id, String... resourceIds) {
        var catalog = new Catalog();
        catalog.setId(id);
        var descriptor = new Descriptor();
        descriptor.setName("Test Catalog " + id);
        catalog.setDescriptor(descriptor);
        var resources = new ArrayList<org.beckn.discover.model.Resource>();
        for (String rid : resourceIds) {
            var r = new org.beckn.discover.model.Resource();
            r.setId(rid);
            resources.add(r);
        }
        catalog.setResources(resources);
        catalog.setOffers(new ArrayList<>());
        return catalog;
    }
}
