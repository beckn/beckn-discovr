package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Resource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link ElasticsearchTextSearchEngine} against a real
 * Elasticsearch instance managed by Testcontainers.
 *
 * <p>
 * Beans are wired manually (no @SpringBootTest) to avoid spinning up
 * PostgreSQL, Kafka, and schema validation — only the ES vertical slice is
 * exercised here.
 * </p>
 *
 * <h3>Test data</h3>
 * 
 * <pre>
 * cat-ev-001 / bpp-ecopower:
 *   ev-charger-001  "DC Fast Charger CCS2 60kW"    full_text_blob has "CCS2" and "EcoPower"
 *   ev-charger-002  "AC Charger Type2 22kW"         full_text_blob has "Type2" and "EcoPower"
 * cat-ev-002 / bpp-greenvolt:
 *   ev-charger-003  "DC Charger CHAdeMO 50kW"       full_text_blob has "CHAdeMO" and "GreenVolt"
 * </pre>
 * 
 * Each test uses a term that is unique to specific docs so results are
 * deterministic.
 */
@Testcontainers
class ElasticsearchTextSearchEngineIntegrationTest {

    private static final String INDEX = "beckn-catalog-test";
    private static final String ALIAS = "beckn-catalog";
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
    private static ElasticsearchTextSearchEngine searchEngine;

    @BeforeAll
    static void setUp() throws Exception {
        RestClient restClient = RestClient.builder(
                HttpHost.create(ES_CONTAINER.getHttpHostAddress())).build();
        esClient = new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));

        searchEngine = new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()), new ObjectMapper(), buildProps(),
                Optional.empty(), Optional.empty());

        createIndexAndAlias();
        seedTestDocs();
        esClient.indices().refresh(r -> r.index(ALIAS));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (esClient != null)
            esClient._transport().close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * "CCS2" is unique to ev-charger-001 (resource_name + full_text_blob).
     * Verifies end-to-end assembly: catalog fields, resource fields, provider,
     * attributes.
     */
    @Test
    void search_uniqueTermMatchesSingleItem_fullAssemblyVerified() throws Exception {
        List<Catalog> catalogs = searchEngine.search("CCS2", queryRequest("tx-1"));

        assertThat(catalogs).hasSize(1);
        Catalog catalog = catalogs.get(0);
        assertThat(catalog.getId()).isEqualTo("cat-ev-001");
        assertThat(catalog.getBppId()).isEqualTo("bpp-ecopower");
        assertThat(catalog.getBppUri()).isEqualTo("https://bpp.ecopower.com");
        assertThat(catalog.getResources()).hasSize(1);

        Resource resource = catalog.getResources().get(0);
        assertThat(resource.getId()).isEqualTo("ev-charger-001");
        assertThat(resource.getDescriptor().getName()).isEqualTo("DC Fast Charger CCS2 60kW");
        assertThat(resource.getDescriptor().getShortDesc()).isEqualTo("60kW DC fast charger for EV");
        assertThat(resource.getProvider().getId()).isEqualTo("ecopower-charging");
        assertThat(resource.getProvider().getDescriptor().getName()).isEqualTo("EcoPower Charging Pvt Ltd");
        assertThat(resource.getIsActive()).isTrue();
    }

    /**
     * "EcoPower" appears ONLY in the full_text_blob of ev-charger-001 and
     * ev-charger-002,
     * both belonging to cat-ev-001. Verifies that 2 items are grouped into 1
     * catalog.
     */
    @Test
    void search_twoItemsShareCatalogId_groupedIntoOneCatalog() throws Exception {
        List<Catalog> catalogs = searchEngine.search("EcoPower", queryRequest("tx-2"));

        assertThat(catalogs).hasSize(1);
        Catalog catalog = catalogs.get(0);
        assertThat(catalog.getId()).isEqualTo("cat-ev-001");
        assertThat(catalog.getBppId()).isEqualTo("bpp-ecopower");
        assertThat(catalog.getResources()).hasSize(2);
        assertThat(catalog.getResources())
                .extracting(Resource::getId)
                .containsExactlyInAnyOrder("ev-charger-001", "ev-charger-002");
    }

    /**
     * "CCS2" matches ev-charger-001 (cat-ev-001); "CHAdeMO" matches ev-charger-003
     * (cat-ev-002).
     * ev-charger-002 has neither term → excluded. Verifies 2 separate catalog
     * objects are returned.
     */
    @Test
    void search_itemsFromDifferentCatalogs_returnsSeparateCatalogObjects() throws Exception {
        List<Catalog> catalogs = searchEngine.search("CCS2 CHAdeMO", queryRequest("tx-3"));

        assertThat(catalogs).hasSize(2);
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-ev-001", "cat-ev-002");
        assertThat(catalogs).extracting(Catalog::getBppId)
                .containsExactlyInAnyOrder("bpp-ecopower", "bpp-greenvolt");
    }

    /**
     * "GreenVolt" appears ONLY in ev-charger-003. Verifies that item_attributes
     * from the ES flat doc are correctly mapped to the resourceAttributes model.
     */
    @Test
    void search_matchingItem_resourceAttributesPopulated() throws Exception {
        List<Catalog> catalogs = searchEngine.search("GreenVolt", queryRequest("tx-4"));

        assertThat(catalogs).hasSize(1);
        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getId()).isEqualTo("ev-charger-003");
        assertThat(resource.getResourceAttributes()).isNotNull();
        assertThat(resource.getResourceAttributes().getAttribute("connectorType")).isEqualTo("CHAdeMO");
        assertThat(resource.getResourceAttributes().getAttribute("maxPowerKW")).isEqualTo(50);
    }

    /**
     * "CCS2" matches ev-charger-001. Verifies rating and category fields are
     * assembled.
     */
    @Test
    void search_matchingItem_ratingAndCategoryPopulated() throws Exception {
        List<Catalog> catalogs = searchEngine.search("CCS2", queryRequest("tx-5"));

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getRating()).isNotNull();
        assertThat(resource.getRating().getRatingValue()).isEqualTo(4.5);
        assertThat(resource.getRating().getRatingCount()).isEqualTo(120);
        assertThat(resource.getCategory()).isNotNull();
        assertThat(resource.getCategory().getCodeValue()).isEqualTo("EV_CHARGING");
        assertThat(resource.getCategory().getName()).isEqualTo("EV Charging");
    }

    @Test
    void search_noMatchingDocuments_returnsEmptyList() throws Exception {
        List<Catalog> catalogs = searchEngine.search("xyznonexistentterm12345", queryRequest("tx-6"));

        assertThat(catalogs).isEmpty();
    }

    @Test
    void search_blankQuery_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> searchEngine.search("  ", queryRequest("tx-7")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null or empty");
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    private static void createIndexAndAlias() throws Exception {
        String mappingJson = """
                {
                  "mappings": {
                    "properties": {
                      "full_text_blob": { "type": "text", "analyzer": "standard" },
                      "resource_name":      { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                      "resource_short_desc":{ "type": "text" },
                      "catalog_id":     { "type": "keyword" },
                      "bpp_id":         { "type": "keyword" },
                      "resource_id":        { "type": "keyword" }
                    }
                  }
                }
                """;
        esClient.indices().create(CreateIndexRequest.of(r -> r
                .index(INDEX)
                .withJson(new StringReader(mappingJson))));
        esClient.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
    }

    private static void seedTestDocs() throws Exception {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : testDocs()) {
            String id = doc.get("bpp_id") + ":" + doc.get("resource_id");
            bulk.operations(op -> op.index(i -> i.index(INDEX).id(id).document(doc)));
        }
        BulkResponse response = esClient.bulk(bulk.build());
        if (response.errors()) {
            throw new IllegalStateException("Bulk seed failed: " + response.items().stream()
                    .filter(i -> i.error() != null).map(i -> i.error().reason())
                    .findFirst().orElse("unknown"));
        }
    }

    /**
     * Term isolation per doc (no overlap across catalogs):
     * - "CCS2" → only ev-charger-001 (cat-ev-001)
     * - "Type2" → only ev-charger-002 (cat-ev-001)
     * - "EcoPower" → ev-charger-001 + ev-charger-002 (both cat-ev-001)
     * - "CHAdeMO" → only ev-charger-003 (cat-ev-002)
     * - "GreenVolt"→ only ev-charger-003 (cat-ev-002)
     */
    private static List<Map<String, Object>> testDocs() {
        return List.of(
                doc("cat-ev-001", "bpp-ecopower", "https://bpp.ecopower.com",
                        "ev-charger-001", "DC Fast Charger CCS2 60kW",
                        "60kW DC fast charger for EV", "CCS2 rapid charge",
                        "EV_CHARGING", "EV Charging", 4.5, 120,
                        "ecopower-charging", "EcoPower Charging Pvt Ltd",
                        Map.of("connectorType", "CCS2", "maxPowerKW", 60),
                        "DC Fast Charger CCS2 60kW EV EcoPower"),

                doc("cat-ev-001", "bpp-ecopower", "https://bpp.ecopower.com",
                        "ev-charger-002", "AC Charger Type2 22kW",
                        "22kW AC charger Type2 for EV", "Type2 AC charge",
                        "EV_CHARGING", "EV Charging", 4.2, 85,
                        "ecopower-charging", "EcoPower Charging Pvt Ltd",
                        Map.of("connectorType", "Type2", "maxPowerKW", 22),
                        "AC Charger Type2 22kW EV EcoPower"),

                doc("cat-ev-002", "bpp-greenvolt", "https://bpp.greenvolt.com",
                        "ev-charger-003", "DC Charger CHAdeMO 50kW",
                        "50kW CHAdeMO DC fast charger", "CHAdeMO rapid charge",
                        "EV_CHARGING", "EV Charging", 3.9, 60,
                        "greenvolt-stations", "GreenVolt Charging Stations",
                        Map.of("connectorType", "CHAdeMO", "maxPowerKW", 50),
                        "DC Charger CHAdeMO 50kW EV GreenVolt"));
    }

    private static Map<String, Object> doc(String catalogId, String bppId, String bppUri,
            String itemId, String itemName,
            String shortDesc, String longDesc,
            String categoryCode, String categoryName,
            double ratingValue, int ratingCount,
            String providerId, String providerName,
            Map<String, Object> itemAttributes,
            String fullTextBlob) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("catalog_id", catalogId);
        m.put("bpp_id", bppId);
        m.put("bpp_uri", bppUri);
        m.put("network_id", "ondc-ev");
        m.put("resource_id", itemId);
        m.put("resource_name", itemName);
        m.put("resource_short_desc", shortDesc);
        m.put("resource_long_desc", longDesc);
        m.put("resource_category_code", categoryCode);
        m.put("resource_category_name", categoryName);
        m.put("resource_rateable", true);
        m.put("resource_is_active", true);
        m.put("resource_rating_value", ratingValue);
        m.put("resource_rating_count", ratingCount);
        m.put("resource_provider_id", providerId);
        m.put("resource_provider_name", providerName);
        m.put("resource_attributes", itemAttributes);
        m.put("full_text_blob", fullTextBlob);
        return m;
    }

    private static DiscoveryProperties buildProps() {
        DiscoveryProperties props = new DiscoveryProperties();
        DiscoveryProperties.Elasticsearch es = new DiscoveryProperties.Elasticsearch();
        es.setHosts(ES_CONTAINER.getHttpHostAddress());
        es.setAliasName(ALIAS);
        es.setResultLimit(50);
        es.setMinScore(0.1f);
        props.setElasticsearch(es);
        return props;
    }

    private static QueryRequest queryRequest(String txId) {
        return new QueryRequest(txId, "msg-" + txId, null, List.of(), null, List.of(), List.of());
    }
}
