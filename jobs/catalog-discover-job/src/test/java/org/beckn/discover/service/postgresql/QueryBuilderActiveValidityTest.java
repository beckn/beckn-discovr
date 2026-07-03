package org.beckn.discover.service.postgresql;

import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryBuilderHelper.QueryTemplate#activeFilter} and
 * {@link QueryBuilderHelper.QueryTemplate#validityFilter} — the {@code ?active}/{@code ?validity}
 * value-match PostgreSQL predicates. Asserts the generated SQL text and bound parameters so the
 * null-safe, value-match, in-query (pre-LIMIT) semantics are locked without a database.
 */
class QueryBuilderActiveValidityTest {

    private static final Instant NOW = Instant.parse("2026-07-02T00:00:00Z");
    private static final OffsetDateTime TS = NOW.atOffset(ZoneOffset.UTC);

    private static QuerySpec build(Boolean activeMatch, Boolean validMatch) {
        return QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(QueryBuilderHelper.JSONPATH_MATCH, "exists($)")
                .activeFilter(activeMatch)
                .validityFilter(validMatch, NOW)
                .build(100);
    }

    @Test
    @DisplayName("both null → no active/validity SQL, only the jsonpath param")
    void noOp_whenBothNull() {
        QuerySpec spec = build(null, null);
        assertThat(spec.sql()).doesNotContain("catalogs,0,isActive");
        assertThat(spec.sql()).doesNotContain("catalogs,0,validity");
        assertThat(spec.parameters()).hasSize(1);
    }

    @Test
    @DisplayName("active=true binds the isActive predicate to true (active-or-absent)")
    void activeTrue() {
        QuerySpec spec = build(Boolean.TRUE, null);
        assertThat(spec.sql()).contains("COALESCE((i.payload #>> '{catalogs,0,isActive}')::boolean, true) = ?");
        assertThat(spec.sql()).doesNotContain("catalogs,0,validity");
        assertThat(spec.parameters()).containsExactly("exists($)", Boolean.TRUE);
    }

    @Test
    @DisplayName("active=false binds the isActive predicate to false (explicitly inactive only)")
    void activeFalse() {
        QuerySpec spec = build(Boolean.FALSE, null);
        assertThat(spec.parameters()).containsExactly("exists($)", Boolean.FALSE);
    }

    @Test
    @DisplayName("validity=true adds both inclusive within-window bounds, binding now twice, pre-LIMIT")
    void validityTrue() {
        QuerySpec spec = build(null, Boolean.TRUE);
        String sql = spec.sql();
        assertThat(sql).contains("catalogs,0,validity,startDate");
        assertThat(sql).contains("catalogs,0,validity,endDate");
        assertThat(sql).contains("::timestamptz <= ?");
        assertThat(sql).contains("::timestamptz >= ?");
        assertThat(sql.indexOf("catalogs,0,validity")).isLessThan(sql.indexOf("LIMIT"));
        assertThat(spec.parameters()).containsExactly("exists($)", TS, TS);
    }

    @Test
    @DisplayName("validity=false adds the out-of-window predicate (start>now OR end<now), binding now twice")
    void validityFalse() {
        QuerySpec spec = build(null, Boolean.FALSE);
        String sql = spec.sql();
        assertThat(sql).contains("::timestamptz > ?");
        assertThat(sql).contains("::timestamptz < ?");
        assertThat(sql).doesNotContain("<= ?");
        assertThat(sql).doesNotContain(">= ?");
        assertThat(spec.parameters()).containsExactly("exists($)", TS, TS);
    }

    @Test
    @DisplayName("active + validity compose as independent AND-ed conditions")
    void activeAndValidity() {
        QuerySpec spec = build(Boolean.TRUE, Boolean.TRUE);
        assertThat(spec.sql()).contains("catalogs,0,isActive");
        assertThat(spec.sql()).contains("catalogs,0,validity");
        // jsonpath + activeMatch + start-now + end-now
        assertThat(spec.parameters()).containsExactly("exists($)", Boolean.TRUE, TS, TS);
    }
}
