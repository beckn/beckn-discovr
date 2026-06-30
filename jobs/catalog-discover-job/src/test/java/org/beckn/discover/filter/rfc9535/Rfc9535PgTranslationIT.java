package org.beckn.discover.filter.rfc9535;

import org.beckn.discover.filter.FilterParseException;
import org.beckn.discover.filter.TranslatedFilter;
import org.beckn.discover.filter.UnsupportedFilterException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PGobject;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PoC integration test for RFC 9535 → PostgreSQL SQL/JSON path translation.
 *
 * <p>Proves three things against a <b>real PostgreSQL</b> (Testcontainers):</p>
 * <ol>
 *   <li><b>Validity</b> — every translated expression is accepted by Postgres
 *       via {@code CAST(? AS jsonpath)} (the authoritative PG grammar check).</li>
 *   <li><b>Execution semantics</b> — translated expressions select the correct
 *       nodes when run against seeded {@code jsonb} documents.</li>
 *   <li><b>Boundaries</b> — invalid RFC 9535 → {@link FilterParseException};
 *       valid-but-inexpressible → {@link UnsupportedFilterException}.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RFC 9535 → PostgreSQL jsonpath translation (PoC)")
class Rfc9535PgTranslationIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.4").asCompatibleSubstituteFor("postgres"));

    private static Connection conn;
    private final Rfc9535PgTranslator translator = new Rfc9535PgTranslator();

    @BeforeAll
    void setUp() throws Exception {
        POSTGRES.start();
        conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seed();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (conn != null) conn.close();
        POSTGRES.stop();
    }

    // ── 1. Validity sweep: translate → CAST(? AS jsonpath) on real PG ──────────

    @Test
    @DisplayName("translates 200+ expressions to PG jsonpath that Postgres accepts")
    void validitySweep() throws Exception {
        List<String> corpus = new ArrayList<>();
        corpus.addAll(loadCurated());
        corpus.addAll(generated());

        List<String[]> failures = new ArrayList<>();
        int ok = 0;
        for (String expr : corpus) {
            String pg;
            try {
                TranslatedFilter t = translator.translate(expr);
                pg = t.expression();
            } catch (RuntimeException e) {
                failures.add(new String[]{expr, "<translate failed>", e.toString()});
                continue;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT CAST(? AS jsonpath)")) {
                ps.setString(1, pg);
                ps.execute();
                ok++;
            } catch (Exception e) {
                failures.add(new String[]{expr, pg, rootMsg(e)});
            }
        }

        System.out.println("\n=== RFC 9535 → PG validity sweep ===");
        System.out.printf("total=%d  ok=%d  failed=%d%n", corpus.size(), ok, failures.size());
        for (String[] f : failures) {
            System.out.println("  FAIL  rfc=" + f[0] + "\n        pg =" + f[1] + "\n        err=" + f[2]);
        }
        assertTrue(corpus.size() >= 200, "corpus should exercise 200+ expressions, was " + corpus.size());
        assertTrue(failures.isEmpty(), failures.size() + " expression(s) failed PG CAST (see log)");
    }

    // ── 1b. FULL corpus EXECUTED against a loaded catalog dataset ──────────────

    @Test
    @DisplayName("executes the full corpus against 120 loaded catalog documents in real PG")
    void executeFullCorpusAgainstData() throws Exception {
        int docCount;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM poc_item")) {
            rs.next();
            docCount = rs.getInt(1);
        }

        List<String> corpus = new ArrayList<>();
        corpus.addAll(loadCurated());
        corpus.addAll(generated());

        int executed = 0, runtimeErrors = 0, matchedAtLeastOne = 0;
        long totalMatchedDocs = 0;
        List<String[]> errors = new ArrayList<>();

        for (String expr : corpus) {
            String pg;
            try {
                pg = translator.translate(expr).expression();
            } catch (RuntimeException e) {
                continue; // (unsupported/parse handled by other tests)
            }
            // ACTUALLY EVALUATE the path against every loaded document.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM poc_item WHERE jsonb_path_exists(payload, CAST(? AS jsonpath))")) {
                ps.setString(1, pg);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    int matches = rs.getInt(1);
                    executed++;
                    totalMatchedDocs += matches;
                    if (matches > 0) matchedAtLeastOne++;
                }
            } catch (Exception e) {
                runtimeErrors++;
                errors.add(new String[]{expr, pg, rootMsg(e)});
            }
        }

        System.out.println("\n=== FULL corpus executed against loaded data ===");
        System.out.printf("documents loaded     = %d%n", docCount);
        System.out.printf("expressions executed = %d%n", executed);
        System.out.printf("runtime errors       = %d%n", runtimeErrors);
        System.out.printf("matched >=1 document = %d%n", matchedAtLeastOne);
        System.out.printf("total (expr×doc) hits= %d%n", totalMatchedDocs);
        for (String[] er : errors) {
            System.out.println("  ERROR rfc=" + er[0] + "\n        pg =" + er[1] + "\n        err=" + er[2]);
        }

        assertTrue(docCount >= 100, "expected 100+ loaded documents, was " + docCount);
        assertTrue(executed >= 200, "expected 200+ executed expressions, was " + executed);
        assertEquals(0, runtimeErrors, "every translated expression must execute without a PG runtime error");
        assertTrue(matchedAtLeastOne > 0, "sanity: some expressions should match loaded data");
    }

    // ── 2. Execution semantics: run translated paths against seeded jsonb ──────

    @Test
    @DisplayName("translated expressions select the correct nodes in real data")
    void executionSemantics() throws Exception {
        // expr → { d1 expected exists, d2 expected exists }
        Map<String, boolean[]> cases = new LinkedHashMap<>();
        cases.put("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"CCS2\"]", new boolean[]{true, false});
        cases.put("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"Type2\"]", new boolean[]{false, true});
        cases.put("$.catalogs[*].resources[*][?@.rating.value >= 4.0]", new boolean[]{true, true});
        cases.put("$.catalogs[*].resources[*][?@.resourceAttributes.power > 30 && @.resourceAttributes.connectorType == \"CCS2\"]", new boolean[]{true, false});
        cases.put("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"CCS2\" || @.resourceAttributes.connectorType == \"Type2\"]", new boolean[]{true, true});
        cases.put("$.catalogs[*].resources[*]['schema:price']", new boolean[]{true, true});              // namespaced key
        cases.put("$.catalogs[*].resources[*][?@['schema:price'] < 50]", new boolean[]{true, true});
        cases.put("$.catalogs[*].resources[*][?match(@.resourceAttributes.connectorType, \"CCS.*\")]", new boolean[]{true, false}); // match() → like_regex
        cases.put("$.catalogs[*].resources[*][?search(@.descriptor.name, \"Charger\")]", new boolean[]{true, false});               // search() → like_regex
        cases.put("$.catalogs[*].resources[*][?length(@.tags) > 2]", new boolean[]{true, false});         // length() → .size()
        cases.put("$.catalogs[*].resources[*][?@.rating]", new boolean[]{true, true});                    // existence
        cases.put("$.catalogs[*].resources[*][?@.nonexistentField]", new boolean[]{false, false});        // existence (none)
        cases.put("$..['beckn:category']", new boolean[]{true, true});                                    // descendant + namespaced

        System.out.println("\n=== execution semantics: RFC 9535 → PG, run against seeded data ===");
        for (Map.Entry<String, boolean[]> e : cases.entrySet()) {
            String pg = translator.translate(e.getKey()).expression();
            boolean d1 = existsFor("d1", pg);
            boolean d2 = existsFor("d2", pg);
            System.out.println("\nRFC : " + e.getKey());
            System.out.println("PG  : " + pg);
            System.out.printf("hits: d1=%s d2=%s%n", d1, d2);
            assertEquals(e.getValue()[0], d1, "d1 mismatch\n  rfc=" + e.getKey() + "\n  pg =" + pg);
            assertEquals(e.getValue()[1], d2, "d2 mismatch\n  rfc=" + e.getKey() + "\n  pg =" + pg);
        }
        System.out.println("\n=== " + cases.size() + " cases verified ===");
    }

    // ── 3a. Boundary: invalid RFC 9535 → FilterParseException ──────────────────

    @Test
    @DisplayName("rejects malformed RFC 9535 with FilterParseException")
    void rejectsInvalid() {
        String[] invalid = {
                "catalogs[*]",                  // missing root $
                "$.catalogs[*",                 // unbalanced bracket
                "$.catalogs[?]",                // empty filter
                "$.a ? (@.b == )",              // dangling comparison
                "$..",                          // descendant with no selector
                "$.a[?@.b === 1]",              // invalid operator ===
                "",                             // empty
        };
        for (String expr : invalid) {
            assertThrows(FilterParseException.class, () -> translator.translate(expr),
                    "expected parse failure for: " + expr);
        }
    }

    // ── 3b. Boundary: valid RFC 9535 but not PG-expressible → Unsupported ──────

    @Test
    @DisplayName("rejects valid-but-inexpressible RFC 9535 with UnsupportedFilterException")
    void rejectsUnsupported() {
        String[] unsupported = {
                "$.catalogs[0:10:2]",                                   // slice step
                "$.catalogs[*].resources[*][?count(@.offers) >= 1]",    // count() nodelist semantics
        };
        for (String expr : unsupported) {
            assertThrows(UnsupportedFilterException.class, () -> translator.translate(expr),
                    "expected unsupported for: " + expr);
        }
    }

    // ── 4. Round-trip: RFC 9535 form maps to the existing PG fixture form ──────

    @Test
    @DisplayName("RFC 9535 fixture translates byte-identical to the legacy PG form")
    void roundTripMatchesLegacyForm() {
        assertEquals(
                "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")",
                translator.translate(
                        "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"CCS2\"]").expression());
        assertEquals("$.a.b.c", translator.translate("$.a.b.c").expression());
        assertEquals("$.**.price", translator.translate("$..price").expression());
        assertEquals("$.r ? (@.p > 1 && @.q == \"x\")",
                translator.translate("$.r[?@.p > 1 && @.q == 'x']").expression());  // single→double quotes
        assertEquals("$.r.\"schema:price\"",
                translator.translate("$.r['schema:price']").expression());          // namespaced key quoting
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean existsFor(String id, String pgJsonPath) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT jsonb_path_exists(payload, CAST(? AS jsonpath)) FROM poc_item WHERE id = ?")) {
            ps.setString(1, pgJsonPath);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + id);
                return rs.getBoolean(1);
            }
        }
    }

    private static void seed() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE poc_item (id text primary key, payload jsonb)");
        }
        insert("d1", """
                {"catalogs":[{"id":"c1","descriptor":{"name":"EV Net"},"resources":[
                  {"id":"r1","descriptor":{"name":"Fast Charger"},
                   "resourceAttributes":{"connectorType":"CCS2","power":50},
                   "rating":{"value":4.5},"available":true,
                   "schema:price":10,"beckn:category":"EV","networkId":"n1",
                   "tags":["a","b","c"],"offers":[{"id":"o1"}]},
                  {"id":"r2","descriptor":{"name":"Slow Charger"},
                   "resourceAttributes":{"connectorType":"CHAdeMO","power":20},
                   "rating":{"value":3.0},"available":false,
                   "schema:price":25,"beckn:category":"EV","networkId":"n1",
                   "tags":["x"],"offers":[]}
                ]}]}
                """);
        insert("d2", """
                {"catalogs":[{"id":"c2","descriptor":{"name":"Type2 Net"},"resources":[
                  {"id":"r3","descriptor":{"name":"Home Plug"},
                   "resourceAttributes":{"connectorType":"Type2","power":7},
                   "rating":{"value":4.0},"available":true,
                   "schema:price":5,"beckn:category":"Home","networkId":"n2",
                   "tags":["t"],"offers":[{"id":"o2"},{"id":"o3"}]}
                ]}]}
                """);
        // Load a realistic, varied catalog dataset so the full corpus executes
        // against hundreds of resources with diverse attribute values.
        loadGeneratedDataset(120);
    }

    /** Insert {@code n} catalog documents with deterministically varied resources. */
    private static void loadGeneratedDataset(int n) throws Exception {
        String[] connectors = {"CCS2", "CHAdeMO", "Type2", "Type1", "GBT", "Tesla"};
        int[] powers = {5, 10, 20, 30, 50, 75, 100, 150, 200, 350};
        double[] ratings = {2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0};
        String[] categories = {"EV", "Home", "Public", "Fleet"};
        String[] names = {"Fast Charger", "Slow Charger", "Home Plug", "Highway DC", "Mall Station"};

        for (int i = 0; i < n; i++) {
            // 1–3 resources per catalog, attributes cycled by index for spread.
            int resCount = 1 + (i % 3);
            StringBuilder res = new StringBuilder();
            for (int r = 0; r < resCount; r++) {
                int k = i + r;
                String conn = connectors[k % connectors.length];
                int power = powers[k % powers.length];
                double rating = ratings[k % ratings.length];
                String cat = categories[k % categories.length];
                String nm = names[k % names.length];
                int price = 5 + (k % 20) * 7;
                boolean available = (k % 2 == 0);
                int tagCount = (k % 4); // 0..3 tags → exercises length()/.size()
                StringBuilder tags = new StringBuilder("[");
                for (int t = 0; t < tagCount; t++) tags.append(t > 0 ? "," : "").append("\"t").append(t).append("\"");
                tags.append("]");
                if (r > 0) res.append(",");
                res.append(String.format(java.util.Locale.ROOT,
                        "{\"id\":\"r%d_%d\",\"descriptor\":{\"name\":\"%s\"},"
                        + "\"resourceAttributes\":{\"connectorType\":\"%s\",\"power\":%d},"
                        + "\"rating\":{\"value\":%s},\"available\":%s,"
                        + "\"schema:price\":%d,\"beckn:category\":\"%s\",\"networkId\":\"n%d\","
                        + "\"tags\":%s,\"offers\":[{\"id\":\"o%d\"}]}",
                        i, r, nm, conn, power, rating, available, price, cat, (i % 5), tags, k));
            }
            String doc = String.format(java.util.Locale.ROOT,
                    "{\"catalogs\":[{\"id\":\"gc%d\",\"descriptor\":{\"name\":\"Cat %d\"},\"resources\":[%s]}]}",
                    i, i, res);
            insert("gen_" + i, doc);
        }
    }

    private static void insert(String id, String json) throws Exception {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(json);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO poc_item(id, payload) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setObject(2, jsonb);
            ps.executeUpdate();
        }
    }

    private static List<String> loadCurated() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = Rfc9535PgTranslationIT.class.getResourceAsStream("/rfc9535/valid_expressions.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        }
        return out;
    }

    /** Generate many combinations to push the corpus past 200 expressions. */
    private static List<String> generated() {
        List<String> out = new ArrayList<>();
        String[] connectors = {"CCS2", "CHAdeMO", "Type2", "Type1", "GBT", "Tesla"};
        String[] ops = {"==", "!=", "<", "<=", ">", ">="};
        int[] powers = {5, 10, 20, 30, 50, 75, 100, 150, 200, 350};
        double[] ratings = {2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0};

        for (String c : connectors) {
            out.add("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c + "\"]");
            out.add("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c
                    + "\" && @.available == true]");
            out.add("$.catalogs[*].resources[*][?match(@.resourceAttributes.connectorType, \"" + c + ".*\")]");
        }
        for (int p : powers) {
            for (String op : ops) {
                out.add("$.catalogs[*].resources[*][?@.resourceAttributes.power " + op + " " + p + "]");
            }
        }
        for (double r : ratings) {
            out.add("$.catalogs[*].resources[*][?@.rating.value >= " + r + "]");
            out.add("$.catalogs[*].resources[*][?@.rating.value >= " + r + " || @.price < 10]");
        }
        for (int i = 0; i < 10; i++) {
            out.add("$.catalogs[" + i + "].resources[*]");
            out.add("$.catalogs[*].resources[" + i + "].descriptor.name");
            out.add("$.catalogs[0:" + (i + 1) + "].resources[*]");
        }
        // Nested AND/OR combinations across connector × power to broaden coverage.
        for (String c : connectors) {
            for (int p : new int[]{20, 50, 100, 200}) {
                out.add("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c
                        + "\" && @.resourceAttributes.power >= " + p + "]");
                out.add("$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c
                        + "\" || @.resourceAttributes.power < " + p + "]");
            }
        }
        // Namespaced-key predicates across thresholds.
        for (int p : powers) {
            out.add("$.catalogs[*].resources[*][?@['schema:price'] <= " + p + "]");
        }
        return out;
    }

    private static String rootMsg(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage();
    }
}
