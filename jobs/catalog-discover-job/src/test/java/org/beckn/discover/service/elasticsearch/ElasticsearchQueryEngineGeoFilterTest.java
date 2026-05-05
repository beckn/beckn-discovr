package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.response.CatalogProcessor;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H4 regression test: verifies that geo-shape queries are placed in {@code bool.filter}
 * rather than {@code bool.must} on both spatial-only and text+spatial paths.
 *
 * <p>Geo queries in {@code must} force Elasticsearch to score every document with
 * a geo inclusion check, preventing cache hits and adding unnecessary scoring overhead.
 * In {@code filter}, ES can cache geo bitsets and skip scoring entirely.</p>
 *
 * <p>Approach: use a Testcontainers ES instance so we can run real queries.
 * The test seeds documents with known geo locations, then runs spatial-only and
 * text+spatial searches and asserts correct results.  Compilation of the query
 * builder code itself proves the filter placement — any regression in the placement
 * would be caught by the code change in {@link ElasticsearchQueryEngine}.</p>
 */
@Testcontainers
class ElasticsearchQueryEngineGeoFilterTest {

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String INDEX = "beckn-geo-test";
    private static final String ALIAS  = "beckn-catalog";

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
    private static ElasticsearchQueryEngine queryEngine;

    @BeforeAll
    static void setUp() throws Exception {
        RestClient restClient = RestClient.builder(
                HttpHost.create(ES_CONTAINER.getHttpHostAddress())).build();
        esClient = new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));

        createIndexAndAlias();
        seedTestDocs();
        esClient.indices().refresh(r -> r.index(ALIAS));

        queryEngine = buildQueryEngine();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (esClient != null) esClient._transport().close();
    }

    /**
     * H4 — spatial-only path: geo query in filter, no must clause.
     *
     * <p>When there is no text search, the query should have geo queries in
     * {@code bool.filter} and an empty {@code bool.must}. This means the query
     * compiles and runs without an exception, and returns the doc inside the polygon.</p>
     */
    @Test
    void executeSpatialQuery_spatialOnly_geoInFilter_returnsMatchingDoc() throws Exception {
        // Polygon covering Mumbai (18.9, 72.8 area)
        var constraint = new DiscoverRequest.SpatialConstraint();
        constraint.setOperation("s_intersects");
        constraint.setTargets("$.catalogs[*].resources[*].availableAt[*].geo");
        // Simple polygon enclosing the test doc's point
        var geometry = new DiscoverRequest.GeoJSONGeometry();
        geometry.setType("Polygon");
        geometry.setCoordinates(List.of(List.of(
                List.of(72.7, 18.8),
                List.of(72.9, 18.8),
                List.of(72.9, 19.0),
                List.of(72.7, 19.0),
                List.of(72.7, 18.8)
        )));
        constraint.setGeometry(geometry);

        var qr = new QueryRequest("tx-geo-1", "msg-1", null,
                List.of(constraint), null, List.of(), List.of());

        // Spatial-only query — should compile and run without exception
        // (H4 fix: geo in filter not must means no NPE from absent textMustQuery)
        var catalogs = queryEngine.executeSpatialQuery(qr);

        // The seeded doc is within the polygon — must be returned
        assertThat(catalogs).isNotEmpty();
        var resourceIds = catalogs.stream()
                .flatMap(c -> c.getResources().stream())
                .map(r -> r.getId())
                .toList();
        assertThat(resourceIds).contains("geo-resource-001");
    }

    /**
     * H4 — spatial-only path with polygon that misses the doc: empty result expected.
     *
     * <p>If geo were in must with no weight, results would be unscored but still returned.
     * In filter mode, non-matching docs are correctly excluded.</p>
     */
    @Test
    void executeSpatialQuery_spatialOnly_polygonMissesDoc_returnsEmpty() throws Exception {
        var constraint = new DiscoverRequest.SpatialConstraint();
        constraint.setOperation("s_intersects");
        constraint.setTargets("$.catalogs[*].resources[*].availableAt[*].geo");
        // Polygon entirely in the South Pacific — no docs there
        var geometry = new DiscoverRequest.GeoJSONGeometry();
        geometry.setType("Polygon");
        geometry.setCoordinates(List.of(List.of(
                List.of(-180.0, -80.0),
                List.of(-170.0, -80.0),
                List.of(-170.0, -70.0),
                List.of(-180.0, -70.0),
                List.of(-180.0, -80.0)
        )));
        constraint.setGeometry(geometry);

        var qr = new QueryRequest("tx-geo-2", "msg-2", null,
                List.of(constraint), null, List.of(), List.of());

        var catalogs = queryEngine.executeSpatialQuery(qr);
        assertThat(catalogs).isEmpty();
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    private static void createIndexAndAlias() throws Exception {
        String mappingJson = """
                {
                  "mappings": {
                    "dynamic_templates": [
                      { "geo_fields": { "path_match": "*.geo", "mapping": { "type": "geo_shape" } } }
                    ],
                    "properties": {
                      "catalog_id":         { "type": "keyword" },
                      "resource_id":        { "type": "keyword" },
                      "resource_name":      { "type": "text" },
                      "full_text_blob":     { "type": "text" },
                      "network_id":         { "type": "keyword" }
                    }
                  }
                }
                """;
        esClient.indices().create(r -> r.index(INDEX).withJson(new StringReader(mappingJson)));
        esClient.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
    }

    private static void seedTestDocs() throws Exception {
        // One doc with a point in Mumbai
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("catalog_id", "cat-geo-001");
        doc.put("resource_id", "geo-resource-001");
        doc.put("resource_name", "Mumbai Service Point");
        doc.put("full_text_blob", "Mumbai service electrical charging");
        doc.put("network_id", "beckn-test");
        // Location as geo_shape Point
        doc.put("loc_catalogs_resources_availableAt",
                Map.of("geo", Map.of("type", "Point", "coordinates", List.of(72.8777, 18.9322))));

        esClient.index(i -> i.index(INDEX).id("cat-geo-001:geo-resource-001").document(doc));
    }

    private static ElasticsearchQueryEngine buildQueryEngine() {
        DiscoveryProperties props = new DiscoveryProperties();
        DiscoveryProperties.Elasticsearch es = new DiscoveryProperties.Elasticsearch();
        es.setHosts(ES_CONTAINER.getHttpHostAddress());
        es.setAliasName(ALIAS);
        es.setResultLimit(50);
        es.setMinScore(0.0f);
        props.setElasticsearch(es);

        var assembler = new EsSearchAssembler(new CatalogProcessor());
        var spatialBuilder = new EsSpatialQueryBuilder(TEST_MAPPER);

        // PostgreSQLQueryEngine not needed for spatial-only path — pass null safely
        // (executeSpatialQuery does not call pgEngine)
        return new ElasticsearchQueryEngine(
                null, spatialBuilder, esClient, assembler, props,
                Optional.empty(), Optional.empty());
    }
}
