package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.response.CatalogProcessor;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the opt-in {@code activeOnly} filter on the Elasticsearch text-search
 * (T) path, against a real ES instance. Proves the same catalog-level {@code isActive}/{@code
 * validity} semantics as the PostgreSQL path, applied in-query (as an ES {@code filter}, before
 * {@code size}). A fixed {@code now} carried on the {@link QueryRequest} makes the validity
 * boundaries deterministic.
 */
@Testcontainers
class EsActiveValidityIntegrationTest {

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String INDEX = "beckn-catalog-active-test";
    private static final String ALIAS = "beckn-catalog";
    private static final Instant NOW = Instant.parse("2026-07-02T00:00:00Z");
    // Separate fixed instant for the startTime/endTime fallback tests: NOW above is exactly
    // midnight, which is a degenerate reference point for wrap-around testing (any window with a
    // non-midnight endTime trivially satisfies "now <= endTime" at 00:00:00). Noon avoids that.
    private static final Instant TOD_NOW = Instant.parse("2026-07-02T12:00:00Z");

    private static final DockerImageName ES_IMAGE = DockerImageName
            .parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");

    @Container
    static final ElasticsearchContainer ES_CONTAINER = new ElasticsearchContainer(ES_IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(3));

    private static ElasticsearchClient esClient;
    private static ElasticsearchTextSearchEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        RestClient restClient = RestClient.builder(HttpHost.create(ES_CONTAINER.getHttpHostAddress())).build();
        esClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        engine = new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()), TEST_MAPPER, buildProps(),
                Optional.empty(), Optional.empty());
        createIndex();
        seedDocs();
        esClient.indices().refresh(r -> r.index(INDEX));
    }

    private static QueryRequest query(boolean activeOnly) {
        // Map the legacy single flag onto the value-match API: activeOnly ⇒ active=TRUE + validity=TRUE.
        // The fixed NOW is now carried on QueryRequest (the engine reads request.now()), replacing the
        // previously-injected fixed Clock — so validity boundaries stay deterministic.
        return new QueryRequest("tx", "msg", null, List.of(), "widget",
                List.of(), List.of(), List.of(), null,
                activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null, NOW);
    }

    /** startTime/endTime fallback query — text term "gadget" isolates these docs from the widget-search tests above. */
    private static QueryRequest todQuery(Boolean validMatch) {
        return new QueryRequest("tx", "msg", null, List.of(), "gadget",
                List.of(), List.of(), List.of(), null, null, validMatch, TOD_NOW);
    }

    @Test
    @DisplayName("activeOnly=false returns all matching catalogs (existing behaviour)")
    void activeOnlyFalse_returnsAll() throws Exception {
        List<Catalog> catalogs = engine.search("widget", query(false));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active", "cat-inactive", "cat-expired", "cat-nofields");
    }

    @Test
    @DisplayName("activeOnly=true drops explicit-inactive and expired catalogs on the ES text path")
    void activeOnlyTrue_dropsInactiveAndExpired() throws Exception {
        List<Catalog> catalogs = engine.search("widget", query(true));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active", "cat-nofields");
        assertThat(catalogs).extracting(Catalog::getId)
                .doesNotContain("cat-inactive", "cat-expired");
    }

    // ── startTime/endTime fallback: priority + UTC wrap-around (all vs TOD_NOW = 12:00 UTC) ─────

    @Test
    @DisplayName("validity=true keeps same-day and wrap-around windows that cover noon; drops the ones that don't")
    void timeOfDay_validityTrue_keepsCoveringWindows() throws Exception {
        List<Catalog> catalogs = engine.search("gadget", todQuery(Boolean.TRUE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("time-same-day-covering", "time-wrap-covering");
    }

    @Test
    @DisplayName("validity=false selects the windows that do NOT cover noon (same-day and wrap-around)")
    void timeOfDay_validityFalse_selectsNonCoveringWindows() throws Exception {
        List<Catalog> catalogs = engine.search("gadget", todQuery(Boolean.FALSE));
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("time-same-day-not-covering", "time-wrap-not-covering");
    }

    @Test
    @DisplayName("startDate — if present — always wins over startTime/endTime, even when the time window covers now")
    void dateFields_alwaysWinOverTimeFields() throws Exception {
        List<Catalog> validTrue = engine.search("gadget", todQuery(Boolean.TRUE));
        assertThat(validTrue).extracting(Catalog::getId).doesNotContain("date-wins-over-time");

        List<Catalog> validFalse = engine.search("gadget", todQuery(Boolean.FALSE));
        assertThat(validFalse).extracting(Catalog::getId).contains("date-wins-over-time");
    }

    // ── ES infra ────────────────────────────────────────────────────────────────

    private static void createIndex() throws Exception {
        String indexJson = """
                {
                  "mappings": {
                    "properties": {
                      "full_text_blob":   { "type": "text" },
                      "resource_name":    { "type": "text" },
                      "catalog_name":     { "type": "text" },
                      "catalog_id":       { "type": "keyword" },
                      "resource_id":      { "type": "keyword" },
                      "network_id":       { "type": "keyword" },
                      "catalog_is_active":{ "type": "boolean" },
                      "catalog_validity": {
                        "properties": {
                          "startDate": { "type": "date" },
                          "endDate":   { "type": "date" },
                          "startTime": { "type": "keyword" },
                          "endTime":   { "type": "keyword" }
                        }
                      }
                    }
                  }
                }
                """;
        esClient.indices().create(CreateIndexRequest.of(r -> r.index(INDEX).withJson(new StringReader(indexJson))));
        esClient.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
    }

    private static void seedDocs() throws Exception {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs()) {
            String id = doc.get("catalog_id") + ":" + doc.get("resource_id");
            bulk.operations(op -> op.index(i -> i.index(INDEX).id(id).document(doc)));
        }
        BulkResponse response = esClient.bulk(bulk.build());
        if (response.errors()) {
            throw new IllegalStateException("Bulk seed failed: " + response.items().stream()
                    .filter(i -> i.error() != null).map(i -> i.error().reason())
                    .findFirst().orElse("unknown"));
        }
    }

    private static List<Map<String, Object>> docs() {
        return List.of(
                // active + valid window (2020..2999) → keep
                doc("cat-active", "r-active", Map.of("catalog_is_active", true),
                        Map.of("startDate", "2020-01-01T00:00:00Z", "endDate", "2999-12-31T23:59:59Z")),
                // explicit inactive → drop
                doc("cat-inactive", "r-inactive", Map.of("catalog_is_active", false), null),
                // expired window → drop
                doc("cat-expired", "r-expired", Map.of(),
                        Map.of("startDate", "2019-01-01T00:00:00Z", "endDate", "2020-12-31T23:59:59Z")),
                // neither field present → keep (default active, no window)
                doc("cat-nofields", "r-nofields", Map.of(), null),
                // startTime/endTime fallback scenarios, evaluated at TOD_NOW = 12:00 UTC noon. Text is
                // "gadget" (not "widget") so these never surface in the widget-search assertions above.
                todDoc("time-same-day-covering", "t-sc", Map.of("startTime", "09:00:00", "endTime", "21:00:00")),
                todDoc("time-same-day-not-covering", "t-snc", Map.of("startTime", "13:00:00", "endTime", "18:00:00")),
                todDoc("time-wrap-covering", "t-wc", Map.of("startTime", "10:00:00", "endTime", "08:00:00")),
                todDoc("time-wrap-not-covering", "t-wnc", Map.of("startTime", "20:00:00", "endTime", "06:00:00")),
                // future startDate (not-yet-valid) + a startTime/endTime window that covers noon —
                // proves the date field wins over the time-of-day fallback.
                todDoc("date-wins-over-time", "t-dw", Map.of(
                        "startDate", "2099-01-01T00:00:00Z", "startTime", "09:00:00", "endTime", "21:00:00")));
    }

    private static Map<String, Object> todDoc(String catalogId, String resourceId, Map<String, Object> validity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("catalog_id", catalogId);
        m.put("catalog_name", catalogId + " catalog");
        m.put("network_id", "ondc-test");
        m.put("resource_id", resourceId);
        m.put("resource_name", "Gadget " + resourceId);
        m.put("full_text_blob", "premium gadget device " + resourceId);
        m.put("catalog_validity", validity);
        return m;
    }

    private static Map<String, Object> doc(String catalogId, String resourceId,
                                           Map<String, Object> activeField, Map<String, Object> validity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("catalog_id", catalogId);
        m.put("catalog_name", catalogId + " catalog");
        m.put("network_id", "ondc-test");
        m.put("resource_id", resourceId);
        m.put("resource_name", "Widget " + resourceId);
        m.put("full_text_blob", "premium widget device " + resourceId);
        m.putAll(activeField);
        if (validity != null) {
            m.put("catalog_validity", validity);
        }
        return m;
    }

    private static DiscoveryProperties buildProps() {
        DiscoveryProperties props = new DiscoveryProperties();
        DiscoveryProperties.Elasticsearch es = new DiscoveryProperties.Elasticsearch();
        es.setHosts(ES_CONTAINER.getHttpHostAddress());
        es.setAliasName(ALIAS);
        es.setResultLimit(50);
        es.setMinScore(0.0f);
        es.setMultiMatchFields(List.of("full_text_blob", "resource_name^2", "catalog_name^2"));
        es.setRelativeScoreThreshold(0.0);
        props.setElasticsearch(es);
        return props;
    }
}
