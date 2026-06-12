package org.beckn.discover.service.postgresql;

import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryBuilderHelper.QueryTemplate#schemaFiltersPaired(List)} — the
 * paired (context, type) schema filter that fixes F-14 (multi-pair schemaContext
 * cross-leak / spec SC-45) on the PostgreSQL path.
 *
 * <p>The previous {@code schemaFilters(types, urls)} emitted two independent
 * {@code IN} clauses ({@code i.type IN (...) AND i.context_url IN (...)}), which let a
 * resource carrying {@code (Grocery-context, Retail-type)} satisfy a request for the
 * pairs {@code Grocery#Grocery} and {@code Retail#Retail}. The paired builder emits one
 * {@code (context = ? AND type = ?)} group per pair, OR'd together.</p>
 */
class QueryBuilderHelperPairedSchemaTest {

    private static final int LIMIT = 50;

    private static final String GROCERY_CTX = "https://schema.beckn.io/Grocery";
    private static final String RETAIL_CTX  = "https://schema.beckn.io/Retail";

    @Test
    void singlePair_emitsOneContextAndTypeClause_inOrder() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(List.of(GROCERY_CTX + "#GroceryResource"))
                .build(LIMIT);

        assertThat(spec.sql())
                .contains("(i.context_url = ? AND i.type = ?)");
        assertThat(spec.parameters())
                .containsExactly(GROCERY_CTX, "GroceryResource");
    }

    @Test
    void multiPair_emitsOrOfPairedClauses_paramsInPairOrder() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(List.of(
                        GROCERY_CTX + "#GroceryResource",
                        RETAIL_CTX + "#RetailResource"))
                .build(LIMIT);

        // One parenthesised OR group: (pair1) OR (pair2) — NOT independent IN clauses.
        assertThat(spec.sql())
                .contains("((i.context_url = ? AND i.type = ?) OR (i.context_url = ? AND i.type = ?))");
        // Params keep context/type adjacency per pair — this is what preserves pairing.
        assertThat(spec.parameters())
                .containsExactly(GROCERY_CTX, "GroceryResource", RETAIL_CTX, "RetailResource");
        // Guard against a regression to the old independent-IN form.
        assertThat(spec.sql()).doesNotContain("i.type IN (");
        assertThat(spec.sql()).doesNotContain("i.context_url IN (");
    }

    @Test
    void contextOnlyUrl_noFragment_emitsContextClauseWithoutType() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(List.of(GROCERY_CTX))
                .build(LIMIT);

        assertThat(spec.sql()).contains("(i.context_url = ?)");
        assertThat(spec.sql()).doesNotContain("i.type = ?");
        assertThat(spec.parameters()).containsExactly(GROCERY_CTX);
    }

    @Test
    void mixedPairs_contextOnlyAndPaired_bothAppear() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(List.of(GROCERY_CTX + "#GroceryResource", RETAIL_CTX))
                .build(LIMIT);

        assertThat(spec.sql())
                .contains("((i.context_url = ? AND i.type = ?) OR i.context_url = ?)");
        assertThat(spec.parameters())
                .containsExactly(GROCERY_CTX, "GroceryResource", RETAIL_CTX);
    }

    @Test
    void emptyOrNull_addsNoSchemaCondition() {
        QuerySpec empty = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(List.of())
                .build(LIMIT);
        assertThat(empty.sql()).doesNotContain("context_url");
        assertThat(empty.parameters()).isEmpty();

        QuerySpec nul = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(null)
                .build(LIMIT);
        assertThat(nul.sql()).doesNotContain("context_url");
        assertThat(nul.parameters()).isEmpty();
    }

    @Test
    void blankEntries_areSkipped() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .schemaFiltersPaired(java.util.Arrays.asList("  ", "", GROCERY_CTX + "#GroceryResource"))
                .build(LIMIT);
        assertThat(spec.parameters()).containsExactly(GROCERY_CTX, "GroceryResource");
    }
}
