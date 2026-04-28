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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── Helpers ──────────────────────────────────────────────────────────────

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
}
