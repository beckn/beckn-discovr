package org.beckn.discover.filter.rfc9535.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.filter.rfc9535.Rfc9535PgTranslator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PGobject;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive RFC 9535 → PostgreSQL <b>result-validation</b> suite.
 *
 * <p>Loads one realistic Beckn catalog document ({@link CatalogDataset}) into a
 * real PostgreSQL, then for every scenario in {@link ScenarioLibrary}:</p>
 * <ol>
 *   <li>translates the RFC 9535 expression to PG SQL/JSON path,</li>
 *   <li>executes {@code jsonb_path_query_array(payload, <translated>)},</li>
 *   <li>asserts the selected nodes/values <b>exactly equal</b> the independently
 *       computed oracle result (no false positives, no false negatives).</li>
 * </ol>
 *
 * <p>The oracle is plain Java over the same JSON, so this validates the
 * translator against an independent source of truth — e.g. a price-range query
 * must return precisely the resources whose price is in range.</p>
 *
 * <p><b>Adopting this package:</b> it depends only on {@link Rfc9535PgTranslator}
 * (production) + a PostgreSQL connection. To extend coverage, add scenarios to
 * {@link ScenarioLibrary}; to change the data, edit {@link CatalogDataset}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RFC 9535 → PostgreSQL exhaustive result validation")
class Rfc9535ComplianceIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.4").asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Connection conn;
    private static CatalogDataset dataset;
    private final Rfc9535PgTranslator translator = new Rfc9535PgTranslator();

    @BeforeAll
    void setUp() throws Exception {
        POSTGRES.start();
        conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataset = CatalogDataset.build();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE poc_dataset (payload jsonb)");
        }
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(dataset.json());
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO poc_dataset(payload) VALUES (?)")) {
            ps.setObject(1, jsonb);
            ps.executeUpdate();
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        if (conn != null) conn.close();
        POSTGRES.stop();
    }

    @Test
    @DisplayName("500+ scenarios: translate → execute → exact result match against oracle")
    void exhaustiveResultValidation() throws Exception {
        List<Scenario> scenarios = ScenarioLibrary.all(dataset);

        int idValidated = 0, valueValidated = 0, execOnly = 0;
        List<String> failures = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();

        for (Scenario sc : scenarios) {
            byCategory.merge(sc.category(), 1, Integer::sum);
            String pg;
            try {
                pg = translator.translate(sc.expr()).expression();
            } catch (RuntimeException e) {
                failures.add("TRANSLATE FAIL [" + sc.category() + "] " + sc.expr() + "  → " + e.getMessage());
                continue;
            }

            JsonNode result;
            try {
                result = queryArray(pg);
            } catch (Exception e) {
                failures.add("EXEC FAIL [" + sc.category() + "] " + sc.expr() + "\n   pg=" + pg
                        + "\n   err=" + rootMsg(e));
                continue;
            }

            if (sc.expectedIds() != null) {
                TreeSet<String> actual = new TreeSet<>();
                for (JsonNode n : result) {
                    if (n.has("id")) actual.add(n.get("id").asText());
                }
                if (!actual.equals(new TreeSet<>(sc.expectedIds()))) {
                    failures.add(mismatch("IDS", sc, pg, sc.expectedIds().toString(), actual.toString()));
                } else {
                    idValidated++;
                }
            } else if (sc.expectedValues() != null) {
                List<String> actual = new ArrayList<>();
                for (JsonNode n : result) actual.add(n.asText());
                actual.sort(Comparator.comparingInt(Integer::parseInt));
                if (!actual.equals(sc.expectedValues())) {
                    failures.add(mismatch("VALUES", sc, pg, sc.expectedValues().toString(), actual.toString()));
                } else {
                    valueValidated++;
                }
            } else {
                if (!result.isArray()) {
                    failures.add("EXEC-ONLY non-array [" + sc.category() + "] " + sc.expr());
                } else {
                    execOnly++;
                }
            }
        }

        System.out.println("\n=== RFC 9535 exhaustive result validation ===");
        System.out.printf("scenarios total      = %d%n", scenarios.size());
        System.out.printf("result-validated     = %d  (ids=%d, values=%d)%n",
                idValidated + valueValidated, idValidated, valueValidated);
        System.out.printf("execution-only       = %d%n", execOnly);
        System.out.printf("failures             = %d%n", failures.size());
        System.out.println("coverage by category:");
        byCategory.forEach((k, v) -> System.out.printf("   %-26s %d%n", k, v));
        if (!failures.isEmpty()) {
            System.out.println("\n--- FAILURES ---");
            failures.forEach(System.out::println);
        }

        assertTrue(scenarios.size() >= 500,
                "expected 500+ scenarios, was " + scenarios.size());
        assertTrue(idValidated + valueValidated >= 450,
                "expected 450+ result-validated scenarios, was " + (idValidated + valueValidated));
        assertTrue(failures.isEmpty(), failures.size() + " scenario(s) failed (see log)");
    }

    private JsonNode queryArray(String pgJsonPath) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT jsonb_path_query_array(payload, CAST(? AS jsonpath)) FROM poc_dataset")) {
            ps.setString(1, pgJsonPath);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return MAPPER.readTree(rs.getString(1));
            }
        }
    }

    private static String mismatch(String kind, Scenario sc, String pg, String expected, String actual) {
        return kind + " MISMATCH [" + sc.category() + "]\n   rfc=" + sc.expr() + "\n   pg =" + pg
                + "\n   expected=" + expected + "\n   actual  =" + actual;
    }

    private static String rootMsg(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage();
    }
}
