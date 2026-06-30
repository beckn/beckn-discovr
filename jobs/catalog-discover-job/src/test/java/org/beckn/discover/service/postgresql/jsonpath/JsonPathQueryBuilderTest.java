package org.beckn.discover.service.postgresql.jsonpath;

import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-context pairing guard for {@link JsonPathQueryBuilder} — backs the
 * JSONPath PostgreSQL routes: case 1 (J, {@link #build}) and chain step 2 for
 * cases 6/7 (J+T / J+G+T, {@link #buildWithAllowlist}).
 *
 * <p>Regression: spec SC-45 / F-14. {@code schemaContext} entries are
 * {@code <@context-url>#<@type>} pairs that must match as a unit. The old
 * {@code context_url IN (...) AND type IN (...)} form lost pairing and let
 * cross-pair combinations (e.g. Grocery-ctx + Retail-type) leak through. These
 * tests assert the SQL now emits per-pair {@code (context_url = ? AND type = ?)}
 * predicates OR'd together — and never the independent IN form.</p>
 */
class JsonPathQueryBuilderTest {

    private static final String GROCERY = "https://schema.org/Grocery";
    private static final String RETAIL  = "https://schema.org/Retail";

    private JsonPathQueryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new JsonPathQueryBuilder(new org.beckn.discover.filter.FilterCompiler(
                new JsonPathConverter(), new org.beckn.discover.filter.rfc9535.Rfc9535PgTranslator()));
    }

    @Test
    @DisplayName("filterType=rfc9535 → builder binds the TRANSLATED PG jsonpath, not the raw RFC form")
    void rfc9535Dialect_isTranslatedThroughBuilder() {
        // Live wiring: PostgreSQLService passes request.filterType() here. An rfc9535
        // expression must reach PG as the translated SQL/JSON path (filter selector
        // [?...] → standalone ? (...)), proving the consumer path routes the dialect.
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"CCS2\"]",
                List.of(), 100, null, "rfc9535");

        // The bound jsonpath predicate is the PG form, wrapped in exists(...).
        assertThat(spec.parameters())
                .contains("exists($.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\"))");
        // It must NOT carry the raw RFC 9535 filter-selector syntax.
        assertThat(spec.parameters().stream().anyMatch(p -> String.valueOf(p).contains("[?@"))).isFalse();
    }

    @Test
    @DisplayName("two pairs → per-pair (context AND type) OR'd; no independent IN; params in order")
    void twoPairs_emitPairedOrPredicate() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#GroceryResource", RETAIL + "#RetailResource"),
                100);

        // pairing preserved
        assertThat(spec.sql())
                .contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?)");
        // the buggy independent form must be gone
        assertThat(spec.sql()).doesNotContain("i.type IN");
        assertThat(spec.sql()).doesNotContain("i.context_url IN");
        // schema params follow in (base, fragment) order per pair
        assertThat(spec.parameters())
                .containsSubsequence(GROCERY, "GroceryResource", RETAIL, "RetailResource");
    }

    @Test
    @DisplayName("cross-pair never expressible: only the two requested pairs appear (Grocery-ctx+Retail-type cannot match)")
    void crossPairCombination_isNotExpressible() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#GroceryResource", RETAIL + "#RetailResource"),
                100);

        // Each context is bound only with its own type — there is no clause pairing
        // GROCERY with RetailResource or RETAIL with GroceryResource.
        String sql = spec.sql();
        int pairCount = sql.split("i.context_url = \\? AND i.type = \\?", -1).length - 1;
        assertThat(pairCount).isEqualTo(2);
        // adjacency in the param list proves the binding: GROCERY→GroceryResource, RETAIL→RetailResource
        List<Object> p = spec.parameters();
        assertThat(p.indexOf("GroceryResource")).isEqualTo(p.indexOf(GROCERY) + 1);
        assertThat(p.indexOf("RetailResource")).isEqualTo(p.indexOf(RETAIL) + 1);
    }

    @Test
    @DisplayName("single pair → one paired predicate, no OR wrapper")
    void singlePair_noOrWrapper() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#GroceryResource"),
                100);

        assertThat(spec.sql()).contains("(i.context_url = ? AND i.type = ?)");
        assertThat(spec.sql()).doesNotContain(" OR (i.context_url");
        assertThat(spec.parameters()).containsSubsequence(GROCERY, "GroceryResource");
    }

    @Test
    @DisplayName("context-only entry (no #type) → context predicate only, any type")
    void contextOnly_matchesAnyType() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY),
                100);

        assertThat(spec.sql()).contains("(i.context_url = ?)");
        assertThat(spec.sql()).doesNotContain("i.type = ?");
        assertThat(spec.parameters()).contains(GROCERY);
    }

    @Test
    @DisplayName("no schemaContext → no context/type predicate at all")
    void noSchemaContext_noSchemaPredicate() {
        QuerySpec spec = builder.build("$.catalogs[*].resources[*]", List.of(), 100);

        assertThat(spec.sql()).doesNotContain("i.context_url");
        assertThat(spec.sql()).doesNotContain("i.type");
    }

    @Test
    @DisplayName("mixed paired + context-only entry → (ctx AND type) OR (ctx)")
    void mixedPairedAndContextOnly() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#GroceryResource", RETAIL),
                100);

        assertThat(spec.sql())
                .contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ?)");
        assertThat(spec.parameters()).containsSubsequence(GROCERY, "GroceryResource", RETAIL);
    }

    @Test
    @DisplayName("three pairs → exactly two OR joins (scales)")
    void threePairs_twoOrJoins() {
        QuerySpec spec = builder.build(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#A", RETAIL + "#B", "https://schema.org/Pharma#C"),
                100);

        int orCount = spec.sql().split(" OR \\(i.context_url", -1).length - 1;
        assertThat(orCount).isEqualTo(2);
    }

    @Test
    @DisplayName("blank/null entries interleaved → skipped; only valid pairs survive")
    void blankEntriesInterleaved_skipped() {
        var entries = new java.util.ArrayList<String>();
        entries.add("");
        entries.add(GROCERY + "#GroceryResource");
        entries.add("   ");
        entries.add(null);

        QuerySpec spec = builder.build("$.catalogs[*].resources[*]", entries, 100);

        // exactly one pair clause survives
        int pairCount = spec.sql().split("i.context_url = \\? AND i.type = \\?", -1).length - 1;
        assertThat(pairCount).isEqualTo(1);
        assertThat(spec.sql()).doesNotContain(" OR (i.context_url");
        assertThat(spec.parameters()).containsSubsequence(GROCERY, "GroceryResource");
    }

    @Test
    @DisplayName("all-blank list → no schema predicate emitted")
    void allBlank_noSchemaPredicate() {
        QuerySpec spec = builder.build("$.catalogs[*].resources[*]", List.of("", "   "), 100);

        assertThat(spec.sql()).doesNotContain("i.context_url");
        assertThat(spec.sql()).doesNotContain("i.type");
    }

    @Test
    @DisplayName("chain step 2 (buildWithAllowlist) also pairs context+type")
    void allowlistVariant_preservesPairing() {
        QuerySpec spec = builder.buildWithAllowlist(
                "$.catalogs[*].resources[*]",
                List.of(GROCERY + "#GroceryResource", RETAIL + "#RetailResource"),
                100,
                List.of("res-1", "res-2"));

        assertThat(spec.sql())
                .contains("(i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?)");
        assertThat(spec.sql()).doesNotContain("i.type IN").doesNotContain("i.context_url IN");
        // allowlist still applied + rank-preserving order
        assertThat(spec.sql()).contains("i.id = ANY(string_to_array(?, '|'))");
        assertThat(spec.sql()).contains("array_position(string_to_array(?, '|'), i.id)");
        assertThat(spec.parameters())
                .containsSubsequence(GROCERY, "GroceryResource", RETAIL, "RetailResource");
    }
}
