package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import org.beckn.discover.service.engine.QueryRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stateless utility that builds the {@code ?active}/{@code ?validity} value-match Elasticsearch
 * filter — the ES twin of the PostgreSQL {@code QueryBuilderHelper.activeFilter(...)} +
 * {@code validityFilter(...)} predicates.
 *
 * <p>Each dimension is applied independently and only when its match is non-null, evaluated at
 * <b>catalog level</b> against the {@code catalog_is_active} and {@code catalog_validity} fields
 * written by the publish job. Semantics are value-match and null-safe per the Beckn spec
 * (core-v2.0.0-lts):</p>
 * <ul>
 *   <li><b>active = TRUE</b> → keep active-or-absent: {@code must_not term(catalog_is_active=false)}
 *       (matches, and therefore excludes, only docs where the field is present and false).</li>
 *   <li><b>active = FALSE</b> → keep explicitly inactive: {@code term(catalog_is_active=false)}
 *       (missing field is not false, so it is excluded — consistent with spec default true).</li>
 *   <li><b>validity = TRUE</b> → currently valid: (startDate absent OR ≤ now) AND (endDate absent
 *       OR ≥ now). Absent/open-ended-inside counts as valid.</li>
 *   <li><b>validity = FALSE</b> → not currently valid: (startDate present AND &gt; now) OR
 *       (endDate present AND &lt; now). Absent/open-ended-inside is excluded (it counts as valid).</li>
 * </ul>
 * {@code startDate} inclusive ({@code lte}), {@code endDate} inclusive ({@code gte}).
 *
 * <p>Returns {@link Optional#empty()} when neither dimension is requested. All values are bound by
 * the ES client — never concatenated. Independent of {@link EsNetworkFilterBuilder}: added as a
 * separate {@code filter} clause; neither gates the other.</p>
 */
public final class EsActiveValidityFilterBuilder {

    static final String FIELD_IS_ACTIVE      = "catalog_is_active";
    static final String FIELD_VALIDITY_START = "catalog_validity.startDate";
    static final String FIELD_VALIDITY_END   = "catalog_validity.endDate";

    private EsActiveValidityFilterBuilder() {
        // utility class — no instances
    }

    /**
     * Builds the active/validity value-match filter, or {@link Optional#empty()} when neither
     * dimension is requested.
     *
     * @param request the query request (carries the nullable {@code activeMatch}/{@code validMatch})
     * @param now      the reference instant validity windows are evaluated against
     */
    public static Optional<Query> build(QueryRequest request, Instant now) {
        if (request == null || (!request.hasActiveMatch() && !request.hasValidMatch())) {
            return Optional.empty();
        }
        Objects.requireNonNull(now, "now must not be null when a filter dimension is set");
        final String nowIso = now.toString();

        List<Query> clauses = new ArrayList<>(2);
        if (request.hasActiveMatch()) {
            clauses.add(activeClause(request.activeMatch()));
        }
        if (request.hasValidMatch()) {
            clauses.add(validityClause(request.validMatch(), nowIso));
        }

        // Combine the requested dimensions under one bool (filter context — no score impact).
        Query combined = Query.of(q -> q.bool(b -> {
            clauses.forEach(b::filter);
            return b;
        }));
        return Optional.of(combined);
    }

    /** active=TRUE → must_not term(is_active=false) (keeps true+missing); active=FALSE → term(is_active=false). */
    private static Query activeClause(boolean activeMatch) {
        if (activeMatch) {
            return Query.of(q -> q.bool(b -> b
                    .mustNot(mn -> mn.term(t -> t.field(FIELD_IS_ACTIVE).value(false)))));
        }
        return Query.of(q -> q.term(t -> t.field(FIELD_IS_ACTIVE).value(false)));
    }

    /**
     * validity=TRUE → within window (absent bounds pass); validity=FALSE → outside a present
     * window (absent bounds do not match, so absent/open-ended-inside is excluded).
     */
    private static Query validityClause(boolean validMatch, String nowIso) {
        if (validMatch) {
            // startDate absent OR startDate <= now
            Query startOk = Query.of(q -> q.bool(b -> b
                    .should(s -> s.bool(nb -> nb.mustNot(mn -> mn.exists(e -> e.field(FIELD_VALIDITY_START)))))
                    .should(s -> s.range(r -> r.untyped(u -> u.field(FIELD_VALIDITY_START).lte(JsonData.of(nowIso)))))
                    .minimumShouldMatch("1")));
            // endDate absent OR endDate >= now
            Query endOk = Query.of(q -> q.bool(b -> b
                    .should(s -> s.bool(nb -> nb.mustNot(mn -> mn.exists(e -> e.field(FIELD_VALIDITY_END)))))
                    .should(s -> s.range(r -> r.untyped(u -> u.field(FIELD_VALIDITY_END).gte(JsonData.of(nowIso)))))
                    .minimumShouldMatch("1")));
            return Query.of(q -> q.bool(b -> b.filter(startOk).filter(endOk)));
        }
        // not currently valid: startDate > now OR endDate < now (present bounds only)
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.range(r -> r.untyped(u -> u.field(FIELD_VALIDITY_START).gt(JsonData.of(nowIso)))))
                .should(s -> s.range(r -> r.untyped(u -> u.field(FIELD_VALIDITY_END).lt(JsonData.of(nowIso)))))
                .minimumShouldMatch("1")));
    }
}
