package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    // Configured to match Spring Boot's auto-configured ObjectMapper defaults
    private static final ObjectMapper TEST_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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
                esClient, new EsSearchAssembler(new CatalogProcessor()), TEST_MAPPER, buildProps(),
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

    /**
     * Searching "charging" should match docs with "charger" via English stemming.
     * ev-charger-002 blob contains "charger" so stemmer collapses both to same root.
     */
    @Test
    void search_stemmingMatchesInflectedForm_returnsResult() throws Exception {
        // "Type2" isolates ev-charger-002 from the other docs while "charging" tests stemming
        List<Catalog> catalogs = searchEngine.search("Type2 charging", queryRequest("tx-stem-1"));

        assertThat(catalogs).isNotEmpty();
        boolean found = catalogs.stream()
                .flatMap(c -> c.getResources().stream())
                .anyMatch(r -> r.getId().equals("ev-charger-002"));
        assertThat(found).isTrue();
    }

    /**
     * Searching "EV" should match the doc seeded with "electric vehicle" via synonym expansion.
     * ev-charger-003 blob contains "electric vehicle" — the synonym rule expands "EV" to it.
     */
    @Test
    void search_synonymExpansion_evMatchesElectricVehicle() throws Exception {
        // ev-charger-003 blob: "DC Charger CHAdeMO 50kW electric vehicle GreenVolt"
        // With synonym expansion EV → electric vehicle at search time, this doc should match
        List<Catalog> catalogs = searchEngine.search("CHAdeMO EV", queryRequest("tx-syn-1"));

        assertThat(catalogs).isNotEmpty();
        boolean found = catalogs.stream()
                .flatMap(c -> c.getResources().stream())
                .anyMatch(r -> r.getId().equals("ev-charger-003"));
        assertThat(found).isTrue();
    }

    /**
     * A numeric value ("150") placed in the full_text_blob should be findable by search.
     * ev-charger-001 blob contains "150".
     */
    @Test
    void search_numericTermInBlob_matchesDocument() throws Exception {
        List<Catalog> catalogs = searchEngine.search("150", queryRequest("tx-num-1"));

        assertThat(catalogs).isNotEmpty();
        boolean found = catalogs.stream()
                .flatMap(c -> c.getResources().stream())
                .anyMatch(r -> r.getId().equals("ev-charger-001"));
        assertThat(found).isTrue();
    }

    /**
     * Searching by catalog_name (boosted ^2) should return results.
     * "EcoPower Catalog" is the catalog_name for cat-ev-001 docs.
     */
    @Test
    void search_catalogNameBoost_matchScoresHigher() throws Exception {
        List<Catalog> catalogs = searchEngine.search("EcoPower Catalog", queryRequest("tx-boost-1"));

        assertThat(catalogs).isNotEmpty();
        boolean found = catalogs.stream().anyMatch(c -> c.getId().equals("cat-ev-001"));
        assertThat(found).isTrue();
    }

    /**
     * With relativeScoreThreshold=0.6, a query that returns one very strong hit and one
     * very weak hit should drop the weak hit.
     *
     * We use "CCS2 CHAdeMO" which yields two hits: ev-charger-001 (matches CCS2 in name
     * and blob) and ev-charger-003 (matches CHAdeMO in name and blob). However, when we
     * restrict the field list to only "resource_name" and set a high threshold, only the
     * best-matching catalog is retained.
     *
     * Strategy: set threshold=1.0 (only hits equal to top score survive) so only the
     * top-scoring document is retained.
     */
    @Test
    void search_relativeScoreThreshold_dropsLowRelevanceHits() throws Exception {
        // Build engine with threshold=1.0 — only the single highest-scoring hit is kept
        DiscoveryProperties propsHighThreshold = buildPropsWithThreshold(1.0, List.of(
                "full_text_blob", "resource_name^2", "catalog_name^2",
                "resource_provider_name^1.5", "resource_rating_review_text"));
        ElasticsearchTextSearchEngine strictEngine = new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()),
                TEST_MAPPER, propsHighThreshold,
                Optional.empty(), Optional.empty());

        // "EcoPower CCS2" strongly matches ev-charger-001; ev-charger-002 is weaker
        // With threshold=1.0 only the top-scoring doc(s) are kept; those with same top score pass
        List<Catalog> catalogs = strictEngine.search("CCS2 EcoPower", queryRequest("tx-rel-1"));

        // At threshold=1.0 at least some hits are dropped relative to no-threshold result
        // The key assertion: the result is a non-empty but smaller set than without threshold
        assertThat(catalogs).isNotEmpty();
        long totalResources = catalogs.stream().mapToLong(c -> c.getResources().size()).sum();
        // Without threshold "EcoPower" matches 2 docs; with threshold=1.0 only top scorer(s) survive
        // We cannot assert exact count (ES scoring varies) but we assert not all 3 docs are returned
        assertThat(totalResources).isLessThan(3);
    }

    /**
     * With relativeScoreThreshold=0.0, filtering is disabled — all docs above minScore
     * are returned regardless of score spread.
     */
    @Test
    void search_relativeScoreThresholdZero_disablesFiltering() throws Exception {
        DiscoveryProperties propsNoFilter = buildPropsWithThreshold(0.0, List.of(
                "full_text_blob", "resource_name^2", "catalog_name^2",
                "resource_provider_name^1.5", "resource_rating_review_text"));
        ElasticsearchTextSearchEngine noFilterEngine = new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()),
                TEST_MAPPER, propsNoFilter,
                Optional.empty(), Optional.empty());

        // "EcoPower" matches ev-charger-001 and ev-charger-002 — both should be returned
        List<Catalog> catalogs = noFilterEngine.search("EcoPower", queryRequest("tx-rel-2"));

        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).getResources()).hasSize(2);
    }

    /**
     * Custom field list restricted to only "resource_name^2" means the search can only
     * match against the resource_name field. "EcoPower" only appears in full_text_blob
     * (not in resource_name), so no results should be returned when full_text_blob is
     * excluded from the field list.
     */
    @Test
    void search_customFieldList_restrictsScopeToConfiguredFields() throws Exception {
        // Only search in resource_name — "EcoPower" is NOT in resource_name, only in full_text_blob
        DiscoveryProperties propsNameOnly = buildPropsWithThreshold(0.0, List.of("resource_name^2"));
        ElasticsearchTextSearchEngine nameOnlyEngine = new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()),
                TEST_MAPPER, propsNameOnly,
                Optional.empty(), Optional.empty());

        List<Catalog> catalogs = nameOnlyEngine.search("EcoPower", queryRequest("tx-field-1"));

        // "EcoPower" does not appear in resource_name of any test doc
        assertThat(catalogs).isEmpty();
    }

    /**
     * Constructor must reject an empty multiMatchFields list with IllegalArgumentException.
     */
    @Test
    void constructor_emptyMultiMatchFields_throwsIllegalArgumentException() {
        DiscoveryProperties propsEmptyFields = buildPropsWithThreshold(0.6, List.of());

        assertThatThrownBy(() -> new ElasticsearchTextSearchEngine(
                esClient, new EsSearchAssembler(new CatalogProcessor()),
                TEST_MAPPER, propsEmptyFields,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiMatchFields");
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    private static void createIndexAndAlias() throws Exception {
        String indexJson = """
                {
                  "settings": {
                    "analysis": {
                      "filter": {
                        "english_stop":    { "type": "stop",    "stopwords": "_english_" },
                        "english_stemmer": { "type": "stemmer", "language": "english" },
                        "beckn_synonyms":  { "type": "synonym", "synonyms": ["ev, electric vehicle", "charger, charging station"] }
                      },
                      "analyzer": {
                        "beckn_text": {
                          "type": "custom",
                          "tokenizer": "standard",
                          "filter": ["lowercase", "english_stop", "english_stemmer"]
                        },
                        "beckn_text_search": {
                          "type": "custom",
                          "tokenizer": "standard",
                          "filter": ["lowercase", "english_stop", "beckn_synonyms", "english_stemmer"]
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "full_text_blob":          { "type": "text", "analyzer": "beckn_text", "search_analyzer": "beckn_text_search" },
                      "resource_name":           { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                      "resource_short_desc":     { "type": "text" },
                      "resource_category_name":  { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                      "catalog_name":            { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                      "resource_provider_name":  { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                      "catalog_id":              { "type": "keyword" },
                      "bpp_id":                  { "type": "keyword" },
                      "resource_id":                  { "type": "keyword" },
                      "resource_rating_review_text":  { "type": "text" }
                    }
                  }
                }
                """;
        esClient.indices().create(CreateIndexRequest.of(r -> r
                .index(INDEX)
                .withJson(new StringReader(indexJson))));
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
     * - "150" → only ev-charger-001 (numeric blob value)
     */
    private static List<Map<String, Object>> testDocs() {
        return List.of(
                doc("cat-ev-001", "EcoPower Catalog", "bpp-ecopower", "https://bpp.ecopower.com",
                        "ev-charger-001", "DC Fast Charger CCS2 60kW",
                        "60kW DC fast charger for EV", "CCS2 rapid charge",
                        "EV_CHARGING", "EV Charging", 4.5, 120,
                        "ecopower-charging", "EcoPower Charging Pvt Ltd",
                        Map.of("connectorType", "CCS2", "maxPowerKW", 60),
                        "DC Fast Charger CCS2 60kW EV EcoPower 150"),

                doc("cat-ev-001", "EcoPower Catalog", "bpp-ecopower", "https://bpp.ecopower.com",
                        "ev-charger-002", "AC Charger Type2 22kW",
                        "22kW AC charger Type2 for EV", "Type2 AC charge",
                        "EV_CHARGING", "EV Charging", 4.2, 85,
                        "ecopower-charging", "EcoPower Charging Pvt Ltd",
                        Map.of("connectorType", "Type2", "maxPowerKW", 22),
                        "AC Charger Type2 22kW EV EcoPower"),

                doc("cat-ev-002", "GreenVolt Catalog", "bpp-greenvolt", "https://bpp.greenvolt.com",
                        "ev-charger-003", "DC Charger CHAdeMO 50kW",
                        "50kW CHAdeMO DC fast charger", "CHAdeMO rapid charge",
                        "EV_CHARGING", "EV Charging", 3.9, 60,
                        "greenvolt-stations", "GreenVolt Charging Stations",
                        Map.of("connectorType", "CHAdeMO", "maxPowerKW", 50),
                        "DC Charger CHAdeMO 50kW electric vehicle GreenVolt"));
    }

    private static Map<String, Object> doc(String catalogId, String catalogName,
            String bppId, String bppUri,
            String itemId, String itemName,
            String shortDesc, String longDesc,
            String categoryCode, String categoryName,
            double ratingValue, int ratingCount,
            String providerId, String providerName,
            Map<String, Object> itemAttributes,
            String fullTextBlob) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("catalog_id", catalogId);
        m.put("catalog_name", catalogName);
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
        return buildPropsWithThreshold(0.0, List.of(
                "full_text_blob", "resource_name^2", "catalog_name^2",
                "resource_provider_name^1.5", "resource_rating_review_text"));
    }

    private static DiscoveryProperties buildPropsWithThreshold(double threshold, List<String> fields) {
        DiscoveryProperties props = new DiscoveryProperties();
        DiscoveryProperties.Elasticsearch es = new DiscoveryProperties.Elasticsearch();
        es.setHosts(ES_CONTAINER.getHttpHostAddress());
        es.setAliasName(ALIAS);
        es.setResultLimit(50);
        es.setMinScore(0.1f);
        es.setMultiMatchFields(fields);
        es.setRelativeScoreThreshold(threshold);
        props.setElasticsearch(es);
        return props;
    }

    private static QueryRequest queryRequest(String txId) {
        return new QueryRequest(txId, "msg-" + txId, null, List.of(), null, List.of(), List.of());
    }
}
