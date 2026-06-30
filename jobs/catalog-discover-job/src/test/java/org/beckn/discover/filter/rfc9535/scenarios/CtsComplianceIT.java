package org.beckn.discover.filter.rfc9535.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.beckn.discover.filter.FilterParseException;
import org.beckn.discover.filter.UnsupportedFilterException;
import org.beckn.discover.filter.rfc9535.Rfc9535PgTranslator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential conformance test against the <b>official JSONPath Compliance Test
 * Suite</b> (RFC 9535) — {@code cts.json} from
 * {@code jsonpath-standard/jsonpath-compliance-test-suite}.
 *
 * <p>For each official case we translate the selector to PostgreSQL SQL/JSON
 * path, execute it against the case's document in a real PostgreSQL, and compare
 * the result (as an order-insensitive multiset) to the spec's expected result.
 * This judges our translator against the standard's own tests rather than ours.</p>
 *
 * <p><b>The guarantee:</b> whenever we accept and run an expression we must
 * return the correct result — i.e. <b>zero mismatches</b> and <b>zero PG
 * execution errors</b>. Features we deliberately don't translate surface as
 * {@code UNSUPPORTED}/{@code PARSE_FAIL} (counted, not failed) per the
 * reject-over-guess policy. Order is not asserted (discovery returns sets).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RFC 9535 official Compliance Test Suite (differential vs PostgreSQL)")
class CtsComplianceIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.4").asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static Connection conn;
    private final Rfc9535PgTranslator translator = new Rfc9535PgTranslator();

    @BeforeAll
    void setUp() throws Exception {
        POSTGRES.start();
        conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (conn != null) conn.close();
        POSTGRES.stop();
    }

    @Test
    @DisplayName("translate → execute → compare against the spec's expected result")
    void differentialAgainstCts() throws Exception {
        JsonNode tests = loadCts().get("tests");

        int validPass = 0, mismatch = 0, execError = 0, unsupported = 0, parseFail = 0;
        int invalidRejected = 0, invalidAccepted = 0;
        List<String> mismatches = new ArrayList<>();
        List<String> execErrors = new ArrayList<>();
        List<String> acceptedInvalid = new ArrayList<>();

        for (JsonNode t : tests) {
            String name = t.path("name").asText();
            String selector = t.path("selector").asText();

            if (t.path("invalid_selector").asBoolean(false)) {
                try {
                    translator.translate(selector);
                    invalidAccepted++;
                    if (acceptedInvalid.size() < 25) acceptedInvalid.add(name + "  ::  " + selector);
                } catch (FilterParseException | UnsupportedFilterException e) {
                    invalidRejected++;
                }
                continue;
            }

            String pg;
            try {
                pg = translator.translate(selector).expression();
            } catch (UnsupportedFilterException e) {
                unsupported++;
                continue;
            } catch (FilterParseException e) {
                parseFail++;
                continue;
            }

            List<String> actual;
            try {
                actual = canonicalMultiset(queryArray(t.path("document"), pg));
            } catch (Exception e) {
                execError++;
                if (execErrors.size() < 25) execErrors.add(name + "\n   pg=" + pg + "\n   err=" + rootMsg(e));
                continue;
            }

            if (matchesExpected(t, actual)) {
                validPass++;
            } else {
                mismatch++;
                if (mismatches.size() < 40) {
                    mismatches.add(name + "\n   sel=" + selector + "\n   pg =" + pg
                            + "\n   expected=" + expectedToString(t) + "\n   actual  =" + actual);
                }
            }
        }

        int validTotal = validPass + mismatch + execError + unsupported + parseFail;
        int translatedRun = validPass + mismatch + execError;
        System.out.println("\n=== RFC 9535 OFFICIAL CTS (differential vs PostgreSQL) ===");
        System.out.printf("valid cases               = %d%n", validTotal);
        System.out.printf("  translated & executed   = %d%n", translatedRun);
        System.out.printf("    PASS (correct result) = %d%n", validPass);
        System.out.printf("    MISMATCH (wrong)      = %d%n", mismatch);
        System.out.printf("    EXEC ERROR            = %d%n", execError);
        System.out.printf("  unsupported (rejected)  = %d%n", unsupported);
        System.out.printf("  parse-fail (grammar gap)= %d%n", parseFail);
        System.out.printf("invalid cases             = %d%n", invalidRejected + invalidAccepted);
        System.out.printf("  rejected (correct)      = %d%n", invalidRejected);
        System.out.printf("  accepted (over-lenient) = %d%n", invalidAccepted);
        if (translatedRun > 0) {
            System.out.printf("ACCURACY when we run      = %.1f%% (%d/%d)%n",
                    100.0 * validPass / translatedRun, validPass, translatedRun);
        }
        if (!mismatches.isEmpty()) {
            System.out.println("\n--- MISMATCHES ---");
            mismatches.forEach(System.out::println);
        }
        if (!execErrors.isEmpty()) {
            System.out.println("\n--- EXEC ERRORS ---");
            execErrors.forEach(System.out::println);
        }
        if (!acceptedInvalid.isEmpty()) {
            System.out.println("\n--- ACCEPTED-INVALID (sample) ---");
            acceptedInvalid.forEach(System.out::println);
        }

        // The core guarantee: when we accept+run an expression, we are never wrong,
        // and the translated path is always valid PostgreSQL.
        assertTrue(mismatch == 0, mismatch + " CTS result mismatches (see log)");
        assertTrue(execError == 0, execError + " CTS execution errors (see log)");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private JsonNode loadCts() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/rfc9535/cts.json")) {
            return MAPPER.readTree(in);
        }
    }

    private JsonNode queryArray(JsonNode document, String pgJsonPath) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT jsonb_path_query_array(CAST(? AS jsonb), CAST(? AS jsonpath))")) {
            ps.setString(1, MAPPER.writeValueAsString(document));
            ps.setString(2, pgJsonPath);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return MAPPER.readTree(rs.getString(1));
            }
        }
    }

    private boolean matchesExpected(JsonNode t, List<String> actual) throws Exception {
        if (t.has("result")) {
            return canonicalMultiset(t.get("result")).equals(actual);
        }
        // 'results' plural: any listed ordering is acceptable
        for (JsonNode candidate : t.get("results")) {
            if (canonicalMultiset(candidate).equals(actual)) return true;
        }
        return false;
    }

    private List<String> canonicalMultiset(JsonNode array) throws Exception {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) out.add(MAPPER.writeValueAsString(n));
        }
        Collections.sort(out);
        return out;
    }

    private String expectedToString(JsonNode t) {
        return t.has("result") ? t.get("result").toString() : t.get("results").toString();
    }

    private static String rootMsg(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage();
    }
}
