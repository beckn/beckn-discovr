package org.beckn.discover.service.networkscope;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.beckn.discover.service.elasticsearch.EsNetworkFilterBuilder;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.QueryBuilderHelper;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathQueryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for network scoping (#309): both engines must restrict results to the
 * requesting {@code context.networkId}, and must stay network-agnostic when none is given.
 */
class NetworkFilterTest {

    private final JsonPathQueryBuilder jsonPath = new JsonPathQueryBuilder(
            new org.beckn.discover.filter.FilterCompiler(
                    new JsonPathConverter(), new org.beckn.discover.filter.rfc9535.Rfc9535PgTranslator()));

    // ── PostgreSQL: QueryTemplate.networkFilter ──────────────────────────────

    @Test
    @DisplayName("PG QueryTemplate: networkFilter adds '? = ANY(i.network_id)' + binds the id")
    void queryTemplate_networkFilter_addsClauseAndParam() {
        QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(QueryBuilderHelper.JSONPATH_MATCH, "exists($)")
                .networkFilter("net-A")
                .build(100);

        assertThat(spec.sql()).contains("? = ANY(i.network_id)");
        assertThat(spec.parameters()).contains("net-A");
    }

    @Test
    @DisplayName("PG QueryTemplate: networkFilter is a no-op for null/blank id")
    void queryTemplate_networkFilter_noopWhenBlank() {
        QuerySpec nullId = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(QueryBuilderHelper.JSONPATH_MATCH, "exists($)").networkFilter(null).build(100);
        QuerySpec blankId = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(QueryBuilderHelper.JSONPATH_MATCH, "exists($)").networkFilter("  ").build(100);

        assertThat(nullId.sql()).doesNotContain("network_id");
        assertThat(blankId.sql()).doesNotContain("network_id");
    }

    @Test
    @DisplayName("PG JsonPathQueryBuilder: networkId threads into the WHERE clause")
    void jsonPathBuilder_appliesNetworkFilter() {
        QuerySpec scoped = jsonPath.build("$.catalogs[*] ? (@.isActive == true)", List.of(), 100, "net-A");
        assertThat(scoped.sql()).contains("? = ANY(i.network_id)");
        assertThat(scoped.parameters()).contains("net-A");

        // network-agnostic overload must NOT add the filter
        QuerySpec unscoped = jsonPath.build("$.catalogs[*] ? (@.isActive == true)", List.of(), 100);
        assertThat(unscoped.sql()).doesNotContain("network_id");
    }

    // ── Elasticsearch: EsNetworkFilterBuilder ────────────────────────────────

    @Test
    @DisplayName("ES EsNetworkFilterBuilder: term(network_id) present when networkId set, empty otherwise")
    void esNetworkFilter_presentOnlyWhenNetworkIdSet() {
        QueryRequest withNet = new QueryRequest(
                "txn", "msg", "$.x", List.of(), null, List.of(), List.of(), List.of(), "net-A");
        QueryRequest noNet = new QueryRequest(
                "txn", "msg", "$.x", List.of(), null, List.of(), List.of(), List.of(), null);

        Optional<Query> present = EsNetworkFilterBuilder.build(withNet);
        Optional<Query> absent  = EsNetworkFilterBuilder.build(noNet);

        assertThat(present).isPresent();
        assertThat(present.get().isTerm()).isTrue();
        assertThat(present.get().term().field()).isEqualTo("network_id");
        assertThat(present.get().term().value().stringValue()).isEqualTo("net-A");
        assertThat(absent).isEmpty();
    }

    // ── QueryRequest.from carries networkId ──────────────────────────────────

    @Test
    @DisplayName("QueryRequest: hasNetworkFilter reflects presence of networkId")
    void queryRequest_hasNetworkFilter() {
        QueryRequest with = new QueryRequest(
                "t", "m", "$.x", List.of(), null, List.of(), List.of(), List.of(), "net-A");
        QueryRequest without = new QueryRequest(
                "t", "m", "$.x", List.of(), null, List.of(), List.of(), List.of(), "  ");
        assertThat(with.hasNetworkFilter()).isTrue();
        assertThat(without.hasNetworkFilter()).isFalse();
    }
}
