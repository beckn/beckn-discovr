package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EsActiveValidityFilterBuilder} — the ES twin of the PostgreSQL
 * active/validity value-match predicates. Asserts the emitted query DSL rather than running a
 * cluster, so the null-safe value-match spec semantics are locked deterministically.
 *
 * <p>These tests lock the emitted DSL <em>shape</em> — the same static query is emitted
 * regardless of what any given document's data looks like, since date-vs-time priority and
 * wrap-around are evaluated per document at query time (inside the bool structure / Painless
 * script), not baked into different generated DSL per scenario. Behavior against real seeded
 * documents is proven in {@code EsActiveValidityIntegrationTest}.</p>
 */
class EsActiveValidityFilterBuilderTest {

    private static final Instant NOW = Instant.parse("2026-07-02T00:00:00Z");

    /** 11-arg canonical ctor: activeMatch + validMatch are the last two components (nullable). */
    private static QueryRequest request(Boolean activeMatch, Boolean validMatch) {
        return new QueryRequest("tx", "msg", null, List.<DiscoverRequest.SpatialConstraint>of(), null,
                List.of(), List.of(), List.of(), null, activeMatch, validMatch);
    }

    private static String toJson(Query query) {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        StringWriter sw = new StringWriter();
        try (JsonGenerator g = mapper.jsonProvider().createGenerator(sw)) {
            query.serialize(g, mapper);
        }
        return sw.toString();
    }

    @Test
    @DisplayName("no filter emitted when neither dimension is set")
    void empty_whenBothNull() {
        assertThat(EsActiveValidityFilterBuilder.build(request(null, null), NOW)).isEmpty();
    }

    @Test
    @DisplayName("null request → empty (defensive)")
    void empty_whenNullRequest() {
        assertThat(EsActiveValidityFilterBuilder.build(null, NOW)).isEmpty();
    }

    @Test
    @DisplayName("active=true → must_not term(catalog_is_active=false) (keeps true+missing)")
    void activeTrue() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(Boolean.TRUE, null), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("must_not");
        assertThat(json).contains("catalog_is_active");
        assertThat(json).doesNotContain("catalog_validity");
    }

    @Test
    @DisplayName("active=false → term(catalog_is_active=false), no must_not (explicitly inactive only)")
    void activeFalse() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(Boolean.FALSE, null), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("catalog_is_active");
        assertThat(json).doesNotContain("must_not");
    }

    @Test
    @DisplayName("validity=true → within-window (lte/gte) with absent-safe exists + minimum_should_match")
    void validityTrue() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(null, Boolean.TRUE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("catalog_validity.startDate");
        assertThat(json).contains("catalog_validity.endDate");
        assertThat(json).contains("lte");
        assertThat(json).contains("gte");
        assertThat(json).contains("exists");
        assertThat(json).contains("minimum_should_match");
        assertThat(json).contains(NOW.toString());
    }

    @Test
    @DisplayName("validity=false → out-of-window (gt/lt), present bounds only, no lte/gte")
    void validityFalse() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(null, Boolean.FALSE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("catalog_validity.startDate");
        assertThat(json).contains("catalog_validity.endDate");
        // out-of-window uses strict gt/lt range ops
        assertThat(json).contains("\"gt\":");
        assertThat(json).contains("\"lt\":");
        // ...and NOT the inclusive within-window ops
        assertThat(json).doesNotContain("\"lte\":");
        assertThat(json).doesNotContain("\"gte\":");
        // "exists" DOES now appear — it gates the startTime/endTime fallback branch (must_not
        // hasDateField, filter hasBothTimeFields) introduced alongside the date-only gt/lt clause.
        assertThat(json).contains("exists");
    }

    @Test
    @DisplayName("both dimensions requested → both clauses present")
    void bothDimensions() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(Boolean.TRUE, Boolean.TRUE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("catalog_is_active");
        assertThat(json).contains("catalog_validity.startDate");
    }

    // ── startTime/endTime fallback (priority + wrap-around shape) ────────────────

    @Test
    @DisplayName("validity=true → DSL includes a painless script query referencing startTime/endTime")
    void validityTrue_includesTimeOfDayScript() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(null, Boolean.TRUE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("script");
        assertThat(json).contains("painless");
        assertThat(json).contains("catalog_validity.startTime");
        assertThat(json).contains("catalog_validity.endTime");
        assertThat(json).contains("wantValid");
        assertThat(json).contains("nowTime");
    }

    @Test
    @DisplayName("validity=false → DSL includes the same script shape (wantValid carries the direction)")
    void validityFalse_includesTimeOfDayScript() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(null, Boolean.FALSE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("script");
        assertThat(json).contains("catalog_validity.startTime");
        assertThat(json).contains("catalog_validity.endTime");
        assertThat(json).contains("wantValid");
    }

    @Test
    @DisplayName("validity clause structurally gates branches via must_not/minimum_should_match")
    void validity_branchesAreGated() {
        Optional<Query> q = EsActiveValidityFilterBuilder.build(request(null, Boolean.TRUE), NOW);
        assertThat(q).isPresent();
        String json = toJson(q.get());
        assertThat(json).contains("must_not");
        assertThat(json).contains("minimum_should_match");
    }
}
