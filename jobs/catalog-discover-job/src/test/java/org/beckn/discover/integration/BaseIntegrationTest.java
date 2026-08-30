package org.beckn.discover.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.model.Resource;
import okhttp3.mockwebserver.MockWebServer;
import org.postgresql.util.PGobject;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    protected static final Path SAMPLE_DATA_DIR = Paths.get("src", "test", "resources", "fixtures", "catalog_db");
    protected static final DateTimeFormatter PG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:15-3.4")
            .asCompatibleSubstituteFor("postgres");

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("catalog_db")
            .withUsername("catalog_user")
            .withPassword("catalog123");

    protected static final MockWebServer NLWEB_SERVER = startMockWebServer();

    static {
        // Start containers lazily - will be started by @DynamicPropertySource or
        // @BeforeAll
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (NLWEB_SERVER != null) {
                    NLWEB_SERVER.shutdown();
                }
            } catch (IOException ignored) {
            }
            if (POSTGRES != null && POSTGRES.isRunning()) {
                POSTGRES.stop();
            }
        }));
    }

    private static MockWebServer startMockWebServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start NLWeb mock server", e);
        }
        return server;
    }

    private static synchronized void ensurePostgresStarted() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Start container before registering properties
        ensurePostgresStarted();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.consumer.group-id", () -> "discovery-service-integration-tests");
        registry.add("discovery.postgresql.host", POSTGRES::getHost);
        registry.add("discovery.postgresql.port", () -> POSTGRES.getMappedPort(5432));
        registry.add("discovery.postgresql.database", POSTGRES::getDatabaseName);
        registry.add("discovery.postgresql.username", POSTGRES::getUsername);
        registry.add("discovery.postgresql.password", POSTGRES::getPassword);
        registry.add("discovery.nlweb.base-url", () -> NLWEB_SERVER.url("/").toString());
        registry.add("discovery.nlweb.ask-endpoint", () -> "/ask");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeAll
    void baseSetUp() throws Exception {
        // Ensure container is started (in case @DynamicPropertySource wasn't called)
        ensurePostgresStarted();

        ensureSampleDataPresent();
        createSchema();
        loadSampleData();
    }

    protected Context buildContext(String transactionId, String messageId) {
        Context context = new Context();
        context.setTransactionId(transactionId);
        context.setMessageId(messageId);
        context.setAction("discover");
        context.setVersion("2.0.0");
        context.setTtl("PT10M");
        context.setTimestamp(OffsetDateTime.of(2025, 8, 14, 10, 30, 0, 0, ZoneOffset.UTC));
        context.setNetworkId("bap.net/ev-charging");
        return context;
    }

    private void ensureSampleDataPresent() {
        if (!Files.exists(SAMPLE_DATA_DIR.resolve("item.json"))) {
            throw new IllegalStateException(
                    "Sample catalog_db item.json is missing under " + SAMPLE_DATA_DIR.toAbsolutePath());
        }
    }

    private static final List<String> MIGRATION_SCRIPTS = List.of(
            "sql/V1__create_catalog_tables.sql");

    private void createSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbcTemplate.execute("DROP TABLE IF EXISTS provider_offer CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS item_location_collection CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS item CASCADE");

        var dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "DataSource must not be null");
        try (Connection connection = dataSource.getConnection()) {
            for (String script : MIGRATION_SCRIPTS) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute migration scripts", e);
        }
        // Exception-safe timestamptz parse used by the ?validity filter (prod: publish-job migration
        // V6). Executed as a single raw statement — ScriptUtils splits on ';' and cannot handle the
        // PL/pgSQL $$-quoted body.
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION try_to_timestamptz(txt text)
                RETURNS timestamptz
                LANGUAGE plpgsql
                IMMUTABLE
                PARALLEL SAFE
                AS $$
                BEGIN
                    RETURN txt::timestamptz;
                EXCEPTION WHEN others THEN
                    RETURN NULL;
                END;
                $$""");
        // Exception-safe time-of-day parse used by the ?validity filter's startTime/endTime
        // fallback, applied only when a catalog's validity has no startDate/endDate (prod:
        // publish-job migration V7).
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION try_to_time(txt text)
                RETURNS time
                LANGUAGE plpgsql
                IMMUTABLE
                PARALLEL SAFE
                AS $$
                BEGIN
                    RETURN txt::time;
                EXCEPTION WHEN others THEN
                    RETURN NULL;
                END;
                $$""");
    }

    private void loadSampleData() throws IOException {
        List<Map<String, Object>> itemRows = readJsonRows("item.json");
        for (Map<String, Object> row : itemRows) {
            insertItemRow(row);
        }

        // Seed item_location_collection — loaded from optional fixture file if present,
        // otherwise falls back to inserting geometry derived from the item fixtures.
        loadItemLocationRows();
    }

    /**
     * Loads geometry rows into {@code item_location_collection}.
     *
     * <p>
     * Reads from {@code catalog_db/item_location_collection.json} when present
     * (preferred — keeps geometry data in one place). Falls back to programmatic
     * derivation from item fixtures when the file is absent.
     * </p>
     *
     * <p>
     * All geometry values are passed as JDBC {@code ?} parameters using
     * {@code ST_SetSRID(ST_MakePoint(?, ?), 4326)} — no user data is ever
     * interpolated into SQL text.
     * </p>
     */
    private void loadItemLocationRows() throws IOException {
        Path ilcFile = SAMPLE_DATA_DIR.resolve("item_location_collection.json");
        if (Files.exists(ilcFile)) {
            List<Map<String, Object>> rows = OBJECT_MAPPER.readValue(
                    Files.newBufferedReader(ilcFile),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> row : rows) {
                insertItemLocationRow(row);
            }
        } else {
            // Fallback: seed geometry for the EV charging test fixture.
            // Path format matches catalog-publish-job and request targets.
            // Used by spatialQueryUsesPostgisTargets — s_dwithin with radius 1000m.
            String path = "$.catalogs[*].resources[*].availableAt[*].geo";
            String catId = "catalog-ev-charging-001";
            insertItemLocationGeom("ev-charger-ccs2-001", catId, path, 77.5946, 12.9716);
            insertItemLocationGeom("ev-charger-ccs2-002", catId, path, 77.5700, 12.9800);
        }
    }

    /**
     * Inserts one location row from a JSON fixture map.
     * Expected keys: {@code item_id}, {@code catalog_id}, {@code path}, {@code lon}, {@code lat}.
     */
    private void insertItemLocationRow(Map<String, Object> row) {
        double lon = ((Number) row.get("lon")).doubleValue();
        double lat = ((Number) row.get("lat")).doubleValue();
        String catalogId = asString(row, "catalog_id");
        insertItemLocationGeom(asString(row, "item_id"), catalogId != null ? catalogId : "", asString(row, "path"), lon, lat);
    }

    /**
     * Inserts a Point geometry into {@code item_location_collection}.
     *
     * <p>
     * Geometry is constructed entirely on the database side via
     * {@code ST_SetSRID(ST_MakePoint(?, ?), 4326)} — longitude and latitude
     * are JDBC bind parameters, not interpolated strings.
     * </p>
     *
     * @param itemId    the item identifier
     * @param catalogId the catalog this item belongs to
     * @param path      the path token (e.g. {@code $.catalogs[*].resources[*].availableAt[*].geo})
     * @param lon       longitude (x), in degrees
     * @param lat       latitude (y), in degrees
     */
    private void insertItemLocationGeom(String itemId, String catalogId, String path, double lon, double lat) {
        jdbcTemplate.update(
                "INSERT INTO item_location_collection (item_id, catalog_id, path, geom) "
                        + "VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)) "
                        + "ON CONFLICT (item_id, catalog_id, path) DO UPDATE SET geom = EXCLUDED.geom",
                itemId, catalogId, path, lon, lat);
    }

    private List<Map<String, Object>> readJsonRows(String fileName) throws IOException {
        Path path = SAMPLE_DATA_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            return List.of();
        }
        return OBJECT_MAPPER.readValue(Files.newBufferedReader(path), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * Canonical network for fixture data — matches the {@code networkId} used by every
     * request fixture and {@link #buildContext}. Discover scopes results by
     * {@code context.networkId} (#309), so seeded rows must carry the network the test
     * queries on, otherwise they are (correctly) filtered out.
     */
    protected static final String DEFAULT_TEST_NETWORK = "bap.net/ev-charging";

    private void insertItemRow(Map<String, Object> row) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO item (id, catalog_id, context_url, type, network_id, offer_ids, payload, created_by, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ARRAY[]::TEXT[], ?, ?, ?, ?) "
                            + "ON CONFLICT (id, catalog_id) DO UPDATE SET "
                            + "payload = EXCLUDED.payload, network_id = EXCLUDED.network_id, updated_at = EXCLUDED.updated_at");
            ps.setString(1, asString(row, "id"));
            ps.setString(2, asString(row, "catalog_id"));
            ps.setString(3, asString(row, "context_url"));
            ps.setString(4, asString(row, "type"));
            ps.setArray(5, connection.createArrayOf("text", resolveNetworkIds(row)));
            ps.setObject(6, toJsonb(row.get("payload")));
            ps.setString(7, asString(row, "created_by"));
            ps.setString(8, asString(row, "updated_by"));
            ps.setTimestamp(9, parseTimestamp(asString(row, "updated_at")));
            return ps;
        });
    }

    /**
     * Resolves a fixture row's {@code network_id} array. Uses the row's value when present
     * (a JSON array or comma string); otherwise defaults to {@link #DEFAULT_TEST_NETWORK}
     * so fixtures stay discoverable under network scoping (#309).
     */
    private String[] resolveNetworkIds(Map<String, Object> row) {
        Object raw = row.get("network_id");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toArray(String[]::new);
        }
        if (raw instanceof String s && !s.isBlank()) {
            return new String[]{s.trim()};
        }
        return new String[]{DEFAULT_TEST_NETWORK};
    }

    private Timestamp parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Timestamp.from(OffsetDateTime.now().toInstant());
        }
        return Timestamp.valueOf(java.time.LocalDateTime.parse(raw.trim(), PG_TIMESTAMP));
    }

    private PGobject toJsonb(Object payload) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            String json = payload instanceof String ? (String) payload : OBJECT_MAPPER.writeValueAsString(payload);
            pgObject.setValue(Objects.requireNonNull(json, "JSON payload cannot be null"));
            return pgObject;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert payload into jsonb", e);
        }
    }

    private String asString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    // ========== TEST ASSERTION HELPERS ==========

    /**
     * Validates that response context has all required fields with proper values.
     * Checks: transactionId, messageId, timestamp, action, version.
     */
    protected void assertResponseContextValid(Context context, Context requestContext) {
        Assertions.assertThat(context).isNotNull();
        Assertions.assertThat(context.getTransactionId())
                .as("Response context should preserve transactionId from request")
                .isEqualTo(requestContext.getTransactionId());
        Assertions.assertThat(context.getMessageId())
                .as("Response context should preserve messageId from request")
                .isEqualTo(requestContext.getMessageId());
        Assertions.assertThat(context.getTimestamp())
                .as("Response context must have timestamp")
                .isNotNull();
        Assertions.assertThat(context.getAction())
                .as("Response context action must be 'on_discover'")
                .isEqualTo("on_discover");
        Assertions.assertThat(context.getVersion())
                .as("Response context should have version")
                .isNotNull();
    }

    /**
     * Validates catalog structure with detailed checks.
     */
    protected void assertCatalogValid(Catalog catalog) {
        Assertions.assertThat(catalog).isNotNull();
        Assertions.assertThat(catalog.getId())
                .as("Catalog must have ID")
                .isNotBlank();
        Assertions.assertThat(catalog.getResources())
                .as("Catalog must have resources")
                .isNotNull()
                .isNotEmpty();

        // Validate each resource has required fields
        for (Resource resource : catalog.getResources()) {
            Assertions.assertThat(resource.getId())
                    .as("Resource must have ID")
                    .isNotBlank();
            Assertions.assertThat(resource.getDescriptor())
                    .as("Resource must have descriptor")
                    .isNotNull();
            Assertions.assertThat(resource.getResourceAttributes())
                    .as("Resource must have resourceAttributes")
                    .isNotNull();
        }
    }

    /**
     * Validates offer structure and relationship to items.
     */
    @SuppressWarnings("unchecked")
    protected void assertOfferValid(Object offerObj, List<String> expectedItemIds) {
        Assertions.assertThat(offerObj).isNotNull();
        Assertions.assertThat(offerObj).isInstanceOf(Map.class);

        Map<String, Object> offer = (Map<String, Object>) offerObj;
        Assertions.assertThat(offer.get("id"))
                .as("Offer must have id")
                .isNotNull();

        Object itemsObj = offer.get("resourceIds");
        Assertions.assertThat(itemsObj)
                .as("Offer must have resourceIds array")
                .isNotNull();
        Assertions.assertThat(itemsObj).isInstanceOf(List.class);

        List<String> offerItems = (List<String>) itemsObj;
        Assertions.assertThat(offerItems)
                .as("Offer items should reference valid item IDs")
                .isNotEmpty()
                .allMatch(expectedItemIds::contains);
    }

    /**
     * Validates response has expected structure and valid data.
     */
    protected void assertDiscoverResponseValid(DiscoverResponse response, Context requestContext) {
        Assertions.assertThat(response).isNotNull();

        // Validate context
        assertResponseContextValid(response.getContext(), requestContext);

        // Validate message structure
        Assertions.assertThat(response.getMessage()).isNotNull();
        Assertions.assertThat(response.getMessage().getCatalogs()).isNotNull();
    }

    /**
     * Validates that catalogs list matches expected size and all are valid.
     */
    protected void assertCatalogsValid(List<Catalog> catalogs, int expectedSize) {
        Assertions.assertThat(catalogs)
                .as("Catalogs should match expected size")
                .hasSize(expectedSize);

        catalogs.forEach(this::assertCatalogValid);
    }
}
