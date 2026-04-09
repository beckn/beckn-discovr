package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import org.beckn.discover.service.engine.QueryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EsSchemaFilterBuilder}.
 *
 * <p>Two test entry points are exercised:</p>
 * <ol>
 *   <li>{@link EsSchemaFilterBuilder#buildSchemaFilters(List, String)} — the primary
 *       method that takes raw schemaContext URLs (with optional {@code #fragment})
 *       and produces precise paired tuple filters.</li>
 *   <li>{@link EsSchemaFilterBuilder#buildSchemaFilters(QueryRequest)} — the convenience
 *       overload that reads pre-split lists from {@code QueryRequest} and reconstructs
 *       pairs when sizes match.</li>
 * </ol>
 *
 * <p>Tests do NOT spin up Elasticsearch — they assert on the query DSL object graph.</p>
 */
class EsSchemaFilterBuilderTest {

    private static final String TX = "tx-unit";

    // ═════════════════════════════════════════════════════════════════════════
    // buildSchemaFilters(List<String> rawUrls, String txId)
    // ═════════════════════════════════════════════════════════════════════════

    // ── Empty / no-op paths ───────────────────────────────────────────────────

    @Test
    void rawUrls_empty_returnsEmptyList() {
        assertThat(EsSchemaFilterBuilder.buildSchemaFilters(List.of(), TX)).isEmpty();
    }

    @Test
    void rawUrls_null_returnsEmptyList() {
        assertThat(EsSchemaFilterBuilder.buildSchemaFilters((List<String>) null, TX)).isEmpty();
    }

    @Test
    void rawUrls_onlyBlankStrings_returnsEmptyList() {
        assertThat(EsSchemaFilterBuilder.buildSchemaFilters(List.of("  ", ""), TX)).isEmpty();
    }

    // ── Single URL with fragment (context + type) ─────────────────────────────

    @Test
    void rawUrls_singleUrlWithFragment_returnsSingleFilterQuery() {
        var filters = buildRaw("https://schema.org/Product#GroceryItem");

        assertThat(filters).hasSize(1);
    }

    @Test
    void rawUrls_singleUrlWithFragment_outerQueryIsBoolShould() {
        var filters = buildRaw("https://schema.org/Product#GroceryItem");
        var outer   = requireBool(filters.get(0));

        assertThat(outer.should()).hasSize(1);
        assertThat(outer.minimumShouldMatch()).isEqualTo("1");
    }

    @Test
    void rawUrls_singleUrlWithFragment_innerPairHasContextAndType() {
        var filters = buildRaw("https://schema.org/Product#GroceryItem");
        var inner   = requireBool(requireBool(filters.get(0)).should().get(0));

        assertThat(inner.must()).hasSize(2);

        var ctxTerm = requireTerm(inner.must().get(0));
        assertThat(ctxTerm.field()).isEqualTo(EsSchemaFilterBuilder.FIELD_CONTEXT);
        assertThat(ctxTerm.value().stringValue()).isEqualTo("https://schema.org/Product");

        var typeTerm = requireTerm(inner.must().get(1));
        assertThat(typeTerm.field()).isEqualTo(EsSchemaFilterBuilder.FIELD_TYPE);
        assertThat(typeTerm.value().stringValue()).isEqualTo("GroceryItem");
    }

    // ── URL without fragment (context-only) ───────────────────────────────────

    @Test
    void rawUrls_urlWithoutFragment_contextOnlyTermQuery() {
        var filters = buildRaw("https://schema.org/Product");
        var outer   = requireBool(filters.get(0));

        assertThat(outer.should()).hasSize(1);
        // Should clause must be a plain term (not a bool.must pair)
        var ctxTerm = requireTerm(outer.should().get(0));
        assertThat(ctxTerm.field()).isEqualTo(EsSchemaFilterBuilder.FIELD_CONTEXT);
        assertThat(ctxTerm.value().stringValue()).isEqualTo("https://schema.org/Product");
    }

    @Test
    void rawUrls_urlWithHashButEmptyFragment_contextOnlyTermQuery() {
        // "https://schema.org/Product#" — hash present but fragment is empty string
        var filters = buildRaw("https://schema.org/Product#");
        var outer   = requireBool(filters.get(0));

        var ctxTerm = requireTerm(outer.should().get(0));
        assertThat(ctxTerm.field()).isEqualTo(EsSchemaFilterBuilder.FIELD_CONTEXT);
        assertThat(ctxTerm.value().stringValue()).isEqualTo("https://schema.org/Product");
    }

    // ── Multiple URLs — paired matching, no cross-matching ────────────────────

    @Test
    void rawUrls_twoUrlsDifferentBaseUrls_twoPairsInShould() {
        var filters = buildRaw(
                "https://schema.org/Product#GroceryItem",
                "https://beckn.org/Mobility#RideService");
        var outer = requireBool(filters.get(0));

        assertThat(outer.should()).hasSize(2);
    }

    @Test
    void rawUrls_twoUrlsDifferentBaseUrls_eachPairIsIsolated() {
        var filters = buildRaw(
                "https://schema.org/Product#GroceryItem",
                "https://beckn.org/Mobility#RideService");
        var outer = requireBool(filters.get(0));

        // First pair: schema.org/Product + GroceryItem
        var pair1 = requireBool(outer.should().get(0));
        assertThat(requireTerm(pair1.must().get(0)).value().stringValue())
                .isEqualTo("https://schema.org/Product");
        assertThat(requireTerm(pair1.must().get(1)).value().stringValue())
                .isEqualTo("GroceryItem");

        // Second pair: beckn.org/Mobility + RideService
        var pair2 = requireBool(outer.should().get(1));
        assertThat(requireTerm(pair2.must().get(0)).value().stringValue())
                .isEqualTo("https://beckn.org/Mobility");
        assertThat(requireTerm(pair2.must().get(1)).value().stringValue())
                .isEqualTo("RideService");
    }

    @Test
    void rawUrls_sameBaseUrlDifferentFragments_twoPairsNoMixing() {
        var filters = buildRaw(
                "https://schema.org/Product#GroceryItem",
                "https://schema.org/Product#ElectronicsItem");
        var outer = requireBool(filters.get(0));

        assertThat(outer.should()).hasSize(2);

        // First pair must have GroceryItem, second must have ElectronicsItem
        var pair1 = requireBool(outer.should().get(0));
        assertThat(requireTerm(pair1.must().get(1)).value().stringValue())
                .isEqualTo("GroceryItem");

        var pair2 = requireBool(outer.should().get(1));
        assertThat(requireTerm(pair2.must().get(1)).value().stringValue())
                .isEqualTo("ElectronicsItem");
    }

    @Test
    void rawUrls_mixedUrlsWithAndWithoutFragment_bothHandledCorrectly() {
        var filters = buildRaw(
                "https://schema.org/Product#GroceryItem",   // paired
                "https://beckn.org/Base");                   // context-only

        var outer = requireBool(filters.get(0));
        assertThat(outer.should()).hasSize(2);

        // First should → bool.must pair (context+type)
        assertThat(outer.should().get(0).bool()).isNotNull();

        // Second should → plain term (context-only)
        assertThat(outer.should().get(1).term()).isNotNull();
    }

    @Test
    void rawUrls_fiftyUrls_handlesUnboundedCardinality() {
        var urls = new java.util.ArrayList<String>();
        for (int i = 0; i < 50; i++) {
            urls.add("https://schema.org/Domain" + i + "#Type" + i);
        }

        var filters = EsSchemaFilterBuilder.buildSchemaFilters(urls, TX);

        assertThat(filters).hasSize(1);
        assertThat(requireBool(filters.get(0)).should()).hasSize(50);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // buildSchemaFilters(QueryRequest)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void queryRequest_emptySchemaContextUrls_returnsEmptyList() {
        var req = queryRequest(List.of(), List.of());

        assertThat(EsSchemaFilterBuilder.buildSchemaFilters(req)).isEmpty();
    }

    @Test
    void queryRequest_nullSchemaContextUrls_returnsEmptyList() {
        // QueryRequest canonical constructor normalises null → List.of()
        var req = new QueryRequest(TX, "msg", null, List.of(), null, List.of(), null);

        assertThat(EsSchemaFilterBuilder.buildSchemaFilters(req)).isEmpty();
    }

    @Test
    void queryRequest_singleContextAndType_buildsPairedFilter() {
        // schemaContextUrls = ["https://schema.org/Product"], schemaTypes = ["GroceryItem"]
        var req = queryRequest(List.of("https://schema.org/Product"), List.of("GroceryItem"));

        var filters = EsSchemaFilterBuilder.buildSchemaFilters(req);

        assertThat(filters).hasSize(1);
        var outer = requireBool(filters.get(0));
        assertThat(outer.should()).hasSize(1);
        // Inner should be a paired bool.must (context+type)
        var inner = requireBool(outer.should().get(0));
        assertThat(inner.must()).hasSize(2);
        assertThat(requireTerm(inner.must().get(0)).value().stringValue())
                .isEqualTo("https://schema.org/Product");
        assertThat(requireTerm(inner.must().get(1)).value().stringValue())
                .isEqualTo("GroceryItem");
    }

    @Test
    void queryRequest_contextOnlyNoTypes_buildsContextOnlyFilter() {
        // schemaContextUrls = ["https://schema.org/Product"], schemaTypes = []
        var req = queryRequest(List.of("https://schema.org/Product"), List.of());

        var filters = EsSchemaFilterBuilder.buildSchemaFilters(req);

        assertThat(filters).hasSize(1);
        var outer = requireBool(filters.get(0));
        // Context-only — should be a plain term query
        var ctxTerm = requireTerm(outer.should().get(0));
        assertThat(ctxTerm.field()).isEqualTo(EsSchemaFilterBuilder.FIELD_CONTEXT);
    }

    @Test
    void queryRequest_twoContextsAndTwoTypes_buildsTwoPairs() {
        var req = queryRequest(
                List.of("https://schema.org/Product", "https://beckn.org/Mobility"),
                List.of("GroceryItem", "RideService"));

        var filters = EsSchemaFilterBuilder.buildSchemaFilters(req);

        assertThat(filters).hasSize(1);
        var outer = requireBool(filters.get(0));
        assertThat(outer.should()).hasSize(2);
    }

    // ── Field name constants ──────────────────────────────────────────────────

    @Test
    void fieldConstants_matchExpectedEsFieldNames() {
        assertThat(EsSchemaFilterBuilder.FIELD_CONTEXT).isEqualTo("resource_attributes_context");
        assertThat(EsSchemaFilterBuilder.FIELD_TYPE).isEqualTo("resource_attributes_type");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Query> buildRaw(String... rawUrls) {
        return EsSchemaFilterBuilder.buildSchemaFilters(List.of(rawUrls), TX);
    }

    private static QueryRequest queryRequest(List<String> schemaContextUrls, List<String> schemaTypes) {
        // Reconstruct raw URLs from base + type for paired matching
        var rawUrls = new java.util.ArrayList<String>();
        if (schemaContextUrls.size() == schemaTypes.size()) {
            for (int i = 0; i < schemaContextUrls.size(); i++) {
                rawUrls.add(schemaContextUrls.get(i) + "#" + schemaTypes.get(i));
            }
        } else {
            rawUrls.addAll(schemaContextUrls);
        }
        return new QueryRequest(TX, "msg-" + TX, null, List.of(), null,
                schemaTypes, schemaContextUrls, rawUrls);
    }

    private static BoolQuery requireBool(Query query) {
        assertThat(query.bool()).as("Expected bool query, was: %s", query).isNotNull();
        return query.bool();
    }

    private static TermQuery requireTerm(Query query) {
        assertThat(query.term()).as("Expected term query, was: %s", query).isNotNull();
        return query.term();
    }
}
