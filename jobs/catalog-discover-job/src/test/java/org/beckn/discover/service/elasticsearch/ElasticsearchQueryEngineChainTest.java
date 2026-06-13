package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the dual-mode behaviour of {@link ElasticsearchQueryEngine#fetchMatchingResourceIds}
 * — chain step 1 for cases 6 (J+T) and 7 (J+G+T).
 *
 * <p>When the {@link EmbeddingClient} bean is present (i.e.
 * {@code discovery.text-search.engine=els-semantic-search}) the chain must enrich
 * + embed the query and run KNN. When absent (native-els) it must run BM25
 * without touching the embedding code. The semantic engine
 * ({@code ElasticsearchTextSearchEngine}, {@code EmbeddingClient},
 * {@code QueryEnricher}) is exercised, not modified.</p>
 *
 * <p>This complements {@link ElasticsearchQueryEngineGeoFilterTest} (which uses
 * Testcontainers for geo placement) by using Mockito to assert which code branch
 * is taken — no ES container is needed to verify branching.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElasticsearchQueryEngineChainTest {

    @Mock private ElasticsearchClient esClient;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private QueryEnricher queryEnricher;
    @Mock private EsSearchAssembler assembler;
    @Mock private EsSpatialQueryBuilder spatialBuilder;

    private DiscoveryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        properties.getElasticsearch().setAliasName("beckn-catalog");
        properties.getElasticsearch().setResultLimit(50);
        properties.getElasticsearch().setMinScore(0.0f);
        properties.getElasticsearch().setMultiMatchFields(List.of(
                "full_text_blob",
                "resource_name^3",
                "provider_name^2"));
        properties.getTextSearch().getEmbeddingModel().setKnnCandidates(500);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ElasticsearchQueryEngine engine(boolean withEmbeddingClient) {
        return new ElasticsearchQueryEngine(
                null,
                spatialBuilder,
                esClient,
                assembler,
                properties,
                withEmbeddingClient ? Optional.of(embeddingClient) : Optional.empty(),
                withEmbeddingClient ? Optional.of(queryEnricher) : Optional.empty());
    }

    private QueryRequest textOnlyRequest(String text) {
        return new QueryRequest("tx-1", "msg-1", null, null, text, List.of(), List.of());
    }

    private QueryRequest textAndSpatialRequest(String text) {
        var sc = new DiscoverRequest.SpatialConstraint();
        sc.setOperation("s_dwithin");
        sc.setDistanceMeters(1000.0);
        return new QueryRequest("tx-2", "msg-2", null, List.of(sc), text, List.of(), List.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubSearchReturning(List<String> resourceIds) throws Exception {
        SearchResponse mockResponse = mock(SearchResponse.class);
        HitsMetadata mockHitsMeta = mock(HitsMetadata.class);
        List<Hit<Map>> hits = new ArrayList<>();
        for (String id : resourceIds) {
            Hit<Map> hit = mock(Hit.class);
            when(hit.source()).thenReturn(Map.of("resource_id", id));
            hits.add(hit);
        }
        when(mockHitsMeta.hits()).thenReturn(hits);
        when(mockResponse.hits()).thenReturn(mockHitsMeta);
        when(esClient.search(any(Function.class), eq(Map.class))).thenReturn(mockResponse);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEMANTIC MODE — EmbeddingClient present
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Semantic mode — discovery.text-search.engine=els-semantic-search")
    class SemanticMode {

        @Test
        @DisplayName("Case 6 (J+T): enriches text, embeds, calls ES KNN, returns IDs")
        void case6_semantic_enrichesEmbedsAndCallsKnn() throws Exception {
            var eng = engine(true);
            when(queryEnricher.enrich("running shoes")).thenReturn("running shoes athletic footwear");
            when(embeddingClient.embed("running shoes athletic footwear"))
                    .thenReturn(Optional.of(List.of(0.1f, 0.2f, 0.3f)));
            stubSearchReturning(List.of("res-1", "res-2", "res-3"));

            List<String> ids = eng.fetchMatchingResourceIds(textOnlyRequest("running shoes"), 50);

            assertThat(ids).containsExactly("res-1", "res-2", "res-3");
            verify(queryEnricher).enrich("running shoes");
            verify(embeddingClient).embed("running shoes athletic footwear");
            verify(esClient).search(any(Function.class), eq(Map.class));
            // BM25 BuildText path must not run — geo builder also must not run for case 6
            verify(spatialBuilder, never()).buildGeoShapeQueries(any());
        }

        @Test
        @DisplayName("Case 7 (J+G+T): semantic mode also builds geo filter for KNN")
        void case7_semantic_includesGeoFilter() throws Exception {
            var eng = engine(true);
            when(queryEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
            when(embeddingClient.embed(any())).thenReturn(Optional.of(List.of(0.5f)));
            when(spatialBuilder.buildGeoShapeQueries(any())).thenReturn(Optional.of(List.of(
                    co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q ->
                            q.matchAll(m -> m)))));
            stubSearchReturning(List.of("geo-res-1"));

            List<String> ids = eng.fetchMatchingResourceIds(textAndSpatialRequest("hotel near me"), 50);

            assertThat(ids).containsExactly("geo-res-1");
            verify(spatialBuilder).buildGeoShapeQueries(any());
            verify(embeddingClient).embed("hotel near me");
        }

        @Test
        @DisplayName("Semantic provider returns empty vector → returns empty list, never queries ES")
        void semantic_emptyVector_returnsEmptyWithoutSearching() throws Exception {
            var eng = engine(true);
            when(queryEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
            when(embeddingClient.embed(any())).thenReturn(Optional.empty());

            List<String> ids = eng.fetchMatchingResourceIds(textOnlyRequest("query that yields no vector"), 50);

            assertThat(ids).isEmpty();
            verify(embeddingClient).embed("query that yields no vector");
            verify(esClient, never()).search(any(Function.class), any(Class.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BM25 MODE — EmbeddingClient absent (native-els)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BM25 mode — discovery.text-search.engine=native-els (EmbeddingClient absent)")
    class Bm25Mode {

        @Test
        @DisplayName("Case 6 (J+T): runs BM25 bool query, never touches embedding code")
        void case6_bm25_doesNotCallEmbedding() throws Exception {
            var eng = engine(false);
            stubSearchReturning(List.of("bm25-res-1", "bm25-res-2"));

            List<String> ids = eng.fetchMatchingResourceIds(textOnlyRequest("coffee"), 50);

            assertThat(ids).containsExactly("bm25-res-1", "bm25-res-2");
            verify(esClient).search(any(Function.class), eq(Map.class));
            // No embedding calls — these mocks aren't even wired into this engine
            verifyNoInteractions(embeddingClient);
            verifyNoInteractions(queryEnricher);
        }

        @Test
        @DisplayName("Case 7 (J+G+T): BM25 mode builds geo filter and runs bool query")
        void case7_bm25_includesGeoFilter() throws Exception {
            var eng = engine(false);
            when(spatialBuilder.buildGeoShapeQueries(any())).thenReturn(Optional.of(List.of(
                    co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q ->
                            q.matchAll(m -> m)))));
            stubSearchReturning(List.of("bm25-geo-1"));

            List<String> ids = eng.fetchMatchingResourceIds(textAndSpatialRequest("hotel"), 50);

            assertThat(ids).containsExactly("bm25-geo-1");
            verify(spatialBuilder).buildGeoShapeQueries(any());
            verify(esClient).search(any(Function.class), eq(Map.class));
        }

        @Test
        @DisplayName("Empty ES hits → returns empty list, no crash")
        void bm25_emptyHits_returnsEmpty() throws Exception {
            var eng = engine(false);
            stubSearchReturning(List.of());

            List<String> ids = eng.fetchMatchingResourceIds(textOnlyRequest("nothing matches"), 50);

            assertThat(ids).isEmpty();
        }

        @Test
        @DisplayName("Misconfigured empty multi-match-fields → fails loud, never queries ES (no match-all leak)")
        void bm25_noTextQueryFields_throwsAndDoesNotSearch() {
            properties.getElasticsearch().setMultiMatchFields(List.of());
            var eng = engine(false);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    eng.fetchMatchingResourceIds(textOnlyRequest("coffee"), 50))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("multi-match-fields");

            verifyNoInteractions(esClient);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOURCE PROJECTION — both modes must restrict _source to [resource_id]
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // KNN OVERFETCH — k must never exceed num_candidates (ES rejects k > candidates)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Semantic KNN overfetch — k <= num_candidates <= ES ceiling")
    class KnnOverfetchBounds {

        private static final int ES_KNN_MAX = 10_000;

        @SuppressWarnings({"rawtypes", "unchecked"})
        private co.elastic.clients.elasticsearch._types.KnnSearch captureKnn(int size) throws Exception {
            var eng = engine(true);
            when(queryEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
            when(embeddingClient.embed(any())).thenReturn(Optional.of(List.of(0.1f, 0.2f)));
            stubSearchReturning(List.of("r1"));

            eng.fetchMatchingResourceIds(textOnlyRequest("anything"), size);

            ArgumentCaptor<Function<SearchRequest.Builder, co.elastic.clients.util.ObjectBuilder<SearchRequest>>> captor =
                    ArgumentCaptor.forClass(Function.class);
            verify(esClient).search(captor.capture(), eq(Map.class));
            SearchRequest built = captor.getValue().apply(new SearchRequest.Builder()).build();
            assertThat(built.knn()).hasSize(1);
            return built.knn().get(0);
        }

        @Test
        @DisplayName("Overfetch size > knn-candidates: num_candidates raised to k (defaults: size 1000 > 500)")
        void overfetchLargerThanCandidates_raisesNumCandidatesToK() throws Exception {
            // knn-candidates = 500 (set in setUp). Real chain size at defaults = 1000.
            var knn = captureKnn(1000);
            assertThat(knn.k()).isEqualTo(1000);
            assertThat(knn.numCandidates()).isGreaterThanOrEqualTo(knn.k());
            assertThat(knn.numCandidates()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Overfetch size < knn-candidates: keeps the larger configured candidate pool")
        void overfetchSmallerThanCandidates_keepsConfiguredPool() throws Exception {
            var knn = captureKnn(50);
            assertThat(knn.k()).isEqualTo(50);
            // configured knn-candidates (500) > k (50) — pool stays at 500
            assertThat(knn.numCandidates()).isEqualTo(500);
            assertThat(knn.numCandidates()).isGreaterThanOrEqualTo(knn.k());
        }

        @Test
        @DisplayName("Overfetch size beyond ES ceiling: both k and num_candidates clamped to 10000")
        void overfetchBeyondCeiling_clampsToEsMax() throws Exception {
            var knn = captureKnn(20_000);
            assertThat(knn.k()).isEqualTo(ES_KNN_MAX);
            assertThat(knn.numCandidates()).isEqualTo(ES_KNN_MAX);
            assertThat(knn.numCandidates()).isGreaterThanOrEqualTo(knn.k());
        }
    }

    @Nested
    @DisplayName("_source projection — IDs only in both modes")
    class SourceProjection {

        @Test
        @DisplayName("Semantic mode: SearchRequest._source includes only resource_id")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void semantic_sourceIncludesOnlyResourceId() throws Exception {
            var eng = engine(true);
            when(queryEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
            when(embeddingClient.embed(any())).thenReturn(Optional.of(List.of(0.1f)));
            stubSearchReturning(List.of("r1"));

            eng.fetchMatchingResourceIds(textOnlyRequest("anything"), 50);

            ArgumentCaptor<Function<SearchRequest.Builder, co.elastic.clients.util.ObjectBuilder<SearchRequest>>> captor =
                    ArgumentCaptor.forClass(Function.class);
            verify(esClient).search(captor.capture(), eq(Map.class));
            SearchRequest built = captor.getValue().apply(new SearchRequest.Builder()).build();
            assertThat(built.source().filter().includes()).containsExactly("resource_id");
            // KNN branch — knn list is populated, query is null
            assertThat(built.knn()).isNotEmpty();
            assertThat(built.query()).isNull();
        }

        @Test
        @DisplayName("BM25 mode: SearchRequest._source includes only resource_id and uses bool query")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void bm25_sourceIncludesOnlyResourceId() throws Exception {
            var eng = engine(false);
            stubSearchReturning(List.of("r1"));

            eng.fetchMatchingResourceIds(textOnlyRequest("anything"), 50);

            ArgumentCaptor<Function<SearchRequest.Builder, co.elastic.clients.util.ObjectBuilder<SearchRequest>>> captor =
                    ArgumentCaptor.forClass(Function.class);
            verify(esClient).search(captor.capture(), eq(Map.class));
            SearchRequest built = captor.getValue().apply(new SearchRequest.Builder()).build();
            assertThat(built.source().filter().includes()).containsExactly("resource_id");
            // BM25 branch — bool query, no knn
            assertThat(built.knn()).isEmpty();
            assertThat(built.query()).isNotNull();
            assertThat(built.query().isBool()).isTrue();
        }
    }
}
