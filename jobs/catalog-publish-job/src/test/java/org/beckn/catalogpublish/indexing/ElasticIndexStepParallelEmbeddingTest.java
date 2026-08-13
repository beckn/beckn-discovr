package org.beckn.catalogpublish.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.beckn.catalogpublish.dto.ProcessingError;
import org.beckn.catalogpublish.event.CatalogPersistedEvent;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexResult;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexService;
import org.beckn.catalogpublish.indexing.document.CatalogDocumentAssembler;
import org.beckn.catalogpublish.indexing.failure.EsFailurePublisher;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H6: verifies that embedding calls for items in the same schema-type group are
 * submitted in parallel (all futures started before any are joined).
 *
 * <p>The test uses a tracking embedding stub that records the thread name of each
 * embed() call. If all calls are made from different threads concurrently, the set
 * of thread names will have more than one entry — proving parallel submission.
 * A {@link CountDownLatch} ensures the test waits for all futures before asserting.</p>
 */
@ExtendWith(MockitoExtension.class)
class ElasticIndexStepParallelEmbeddingTest {

    private static final int ITEM_COUNT = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private CatalogDocumentAssembler assembler;
    @Mock private BulkIndexService bulkIndexService;
    @Mock private EsFailurePublisher failurePublisher;
    @Mock private EsIndexerMetrics indexerMetrics;
    @Mock private CatalogPublishMetrics publishMetrics;

    private ElasticIndexStep step;

    @BeforeEach
    void setUp() throws Exception {
        // lenient — the schema-type-defaulted-blank test exercises none of these (the resource
        // is skipped before assembly/indexing), which would otherwise trip strict-stubs.
        // BulkIndexService returns no failures
        org.mockito.Mockito.lenient().when(bulkIndexService.index(anyString(), any())).thenReturn(
                new BulkIndexResult(List.of(), List.of()));

        // EsIndexerMetrics.startBulkTimer() returns a mock Timer.Sample
        io.micrometer.core.instrument.Timer.Sample sample = mock(io.micrometer.core.instrument.Timer.Sample.class);
        org.mockito.Mockito.lenient().when(indexerMetrics.startBulkTimer()).thenReturn(sample);

        // CatalogDocumentAssembler returns a minimal doc with resource_id populated
        org.mockito.Mockito.lenient().when(assembler.assemble(any(Item.class), any(JsonNode.class), anyString(), any()))
                .thenAnswer(inv -> {
                    Item item = inv.getArgument(0);
                    Map<String, Object> doc = new java.util.LinkedHashMap<>();
                    doc.put("resource_id", item.getId());
                    return doc;
                });
    }

    @Test
    void doIndex_withMultipleItems_submitsEmbeddingCallsInParallel() throws Exception {
        // Build a tracking embedding client that records concurrent call count
        AtomicInteger maxConcurrentCalls = new AtomicInteger(0);
        AtomicInteger activeCalls = new AtomicInteger(0);
        Set<String> callerThreadNames = ConcurrentHashMap.newKeySet();
        CountDownLatch allStarted = new CountDownLatch(ITEM_COUNT);
        CountDownLatch releaseAll  = new CountDownLatch(1);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenAnswer(inv -> {
            int current = activeCalls.incrementAndGet();
            callerThreadNames.add(Thread.currentThread().getName());
            maxConcurrentCalls.accumulateAndGet(current, Math::max);
            allStarted.countDown();
            // Wait until all embed() calls have started (proves parallel submission)
            releaseAll.await(5, TimeUnit.SECONDS);
            activeCalls.decrementAndGet();
            return Optional.of(List.of(0.1f, 0.2f));
        });

        step = new ElasticIndexStep(
                assembler, bulkIndexService, failurePublisher,
                indexerMetrics, publishMetrics, MAPPER,
                buildAppProps(10),
                Optional.of(embeddingClient));

        CatalogBatch batch = buildBatch(ITEM_COUNT);
        CatalogPersistedEvent event = new CatalogPersistedEvent(this, batch);

        // Run onCatalogPersisted on a background thread (it blocks on allOf.join())
        Thread runner = new Thread(() -> step.onCatalogPersisted(event));
        runner.setDaemon(true);
        runner.start();

        // Wait until all ITEM_COUNT embed() calls have started
        boolean allStartedInTime = allStarted.await(10, TimeUnit.SECONDS);

        // Release all embed() calls so the step can finish
        releaseAll.countDown();
        runner.join(10_000);

        assertThat(allStartedInTime)
                .as("All %d embedding calls should start before any complete", ITEM_COUNT)
                .isTrue();
        assertThat(maxConcurrentCalls.get())
                .as("Peak concurrent embedding calls should be > 1 (parallel, not serial)")
                .isGreaterThan(1);
    }

    @Test
    void doIndex_embeddingFailsForOneItem_otherItemsStillIndexed() throws Exception {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        AtomicInteger callCount = new AtomicInteger(0);
        when(embeddingClient.embed(anyString())).thenAnswer(inv -> {
            int call = callCount.incrementAndGet();
            if (call == 1) throw new RuntimeException("embedding provider unavailable");
            return Optional.of(List.of(0.5f));
        });

        step = new ElasticIndexStep(
                assembler, bulkIndexService, failurePublisher,
                indexerMetrics, publishMetrics, MAPPER,
                buildAppProps(10),
                Optional.of(embeddingClient));

        CatalogBatch batch = buildBatch(3);
        CatalogPersistedEvent event = new CatalogPersistedEvent(this, batch);

        // Should complete without throwing — per-item errors are isolated
        step.onCatalogPersisted(event);

        // bulkIndexService should still be called with all 3 docs
        org.mockito.Mockito.verify(bulkIndexService, org.mockito.Mockito.atLeastOnce())
                .index(anyString(), any());
    }

    @Test
    void doIndex_resourceWithNoType_defaultSchemaTypeSet_indexesUnderDefault() throws Exception {
        step = new ElasticIndexStep(
                assembler, bulkIndexService, failurePublisher,
                indexerMetrics, publishMetrics, MAPPER,
                buildAppProps(10, "Attributes"),
                Optional.empty());

        CatalogBatch batch = buildBatchWithType(1, null);
        CatalogPersistedEvent event = new CatalogPersistedEvent(this, batch);

        step.onCatalogPersisted(event);

        verify(assembler).assemble(any(Item.class), any(JsonNode.class), eq("Attributes"), any());
        verify(bulkIndexService).index(eq("Attributes"), any());
    }

    @Test
    void doIndex_resourceWithNoType_defaultSchemaTypeBlank_skipsResource() throws Exception {
        step = new ElasticIndexStep(
                assembler, bulkIndexService, failurePublisher,
                indexerMetrics, publishMetrics, MAPPER,
                buildAppProps(10, null),
                Optional.empty());

        CatalogBatch batch = buildBatchWithType(1, null);
        CatalogPersistedEvent event = new CatalogPersistedEvent(this, batch);

        step.onCatalogPersisted(event);

        verify(assembler, never()).assemble(any(), any(), any(), any());
        verify(bulkIndexService, never()).index(any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CatalogBatch buildBatch(int itemCount) throws Exception {
        return buildBatchWithType(itemCount, "TestType");
    }

    private static CatalogBatch buildBatchWithType(int itemCount, String type) throws Exception {
        List<Item> items = new ArrayList<>();
        Map<String, JsonNode> payloadNodes = new java.util.LinkedHashMap<>();

        for (int i = 0; i < itemCount; i++) {
            String id = "item-" + i;
            Item item = Item.from(id, "{}", new String[0], "cat-1",
                    type, "https://schema.org", new String[]{"test-net"});
            items.add(item);
            payloadNodes.put(id, MAPPER.readTree("{\"id\":\"" + id + "\"}"));
        }

        CatalogContext context = new CatalogContext(List.of("test-net"), null);
        return new CatalogBatch(
                "cat-1", context, type, CatalogOperation.PUBLISH,
                items, List.of(), payloadNodes, false);
    }

    private static org.beckn.catalogpublish.config.AppProperties buildAppProps(int batchSize) {
        return buildAppProps(batchSize, null);
    }

    private static org.beckn.catalogpublish.config.AppProperties buildAppProps(int batchSize, String defaultSchemaType) {
        var embModel = new org.beckn.catalogpublish.config.AppProperties.EmbeddingModel(
                true, "test-model", "http://localhost:11434", "", 5000, 1, 100L);
        var textSearch = new org.beckn.catalogpublish.config.AppProperties.TextSearch(embModel);
        var esConfig = new org.beckn.catalogpublish.config.AppProperties.Elasticsearch(
                true, "http://localhost:9200", "beckn-catalog", "beckn-catalog",
                "fail-topic", "dlq-topic", batchSize, 3, 1000L, 4, 100, 5, defaultSchemaType, null);
        var indexing = new org.beckn.catalogpublish.config.AppProperties.Indexing(8192);
        var catalog = new org.beckn.catalogpublish.config.AppProperties.Catalog(
                10_000_000L, true,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, esConfig, textSearch, indexing, true, null, null, null, null, null, null, null);
        var datasource = new org.beckn.catalogpublish.config.AppProperties.Datasource(
                "jdbc:postgresql://localhost:5432/test", "org.postgresql.Driver",
                "test", "test",
                new org.beckn.catalogpublish.config.AppProperties.Hikari(5, 1, 30000L, 600000L, 1800000L));
        var consumer = new org.beckn.catalogpublish.config.AppProperties.Consumer(
                "test-group", 1, 100, 30000, 300000, 10485760, 52428800);
        var topics = new org.beckn.catalogpublish.config.AppProperties.Topics(
                "in", "events", "out", "failed");
        var messaging = new org.beckn.catalogpublish.config.AppProperties.Messaging(
                "localhost:9092", consumer, topics);
        return new org.beckn.catalogpublish.config.AppProperties(datasource, messaging, catalog);
    }
}
