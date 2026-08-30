package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import org.beckn.discover.service.engine.QueryRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p><b>startTime/endTime fallback</b> (core-v2.0.0-lts {@code TimePeriod} also allows a daily
 * recurring time-of-day window). Priority: {@code startDate}/{@code endDate} — if either is
 * present — always wins over {@code startTime}/{@code endTime}, even when both are present
 * together; the time-of-day window is evaluated only when both date fields are absent; a catalog
 * with nothing usable present counts as valid. Comparison is UTC and wrap-around aware — a
 * same-day window when {@code startTime <= endTime} (e.g. {@code 09:00-21:00}), otherwise treated
 * as spanning midnight (e.g. {@code 22:00-02:00}). Evaluated via a Painless script query (the
 * fields are {@code keyword}, not comparable with a static ES range query), using plain
 * lexicographic string compare on zero-padded {@code HH:mm:ss} — equivalent to a time-of-day
 * compare for that fixed-width format. Either bound absent or not exactly 8 characters ⇒ not
 * evaluable ⇒ mirrors the date predicates' null-safe handling (never dropped by
 * {@code validity=true}, never selected by {@code validity=false}).
 *
 * <p>Returns {@link Optional#empty()} when neither dimension is requested. All values are bound by
 * the ES client — never concatenated. Independent of {@link EsNetworkFilterBuilder}: added as a
 * separate {@code filter} clause; neither gates the other.</p>
 */
public final class EsActiveValidityFilterBuilder {

    static final String FIELD_IS_ACTIVE           = "catalog_is_active";
    static final String FIELD_VALIDITY_START      = "catalog_validity.startDate";
    static final String FIELD_VALIDITY_END        = "catalog_validity.endDate";
    static final String FIELD_VALIDITY_START_TIME = "catalog_validity.startTime";
    static final String FIELD_VALIDITY_END_TIME   = "catalog_validity.endTime";

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Painless: null/malformed-safe daily time-of-day window check, wrap-around aware. {@code
     * params.wantValid} selects the direction — when either field is absent or not exactly 8
     * characters (a well-formed zero-padded {@code HH:mm:ss}), the catalog is "not evaluable" and
     * the script returns {@code params.wantValid} itself, mirroring the PostgreSQL twin's
     * null-safe semantics (absent/malformed always counts as valid, never as out-of-window).
     */
    private static final String TIME_WINDOW_SCRIPT =
            "def st = doc['" + FIELD_VALIDITY_START_TIME + "']; "
          + "def et = doc['" + FIELD_VALIDITY_END_TIME + "']; "
          + "if (st.size() == 0 || et.size() == 0) { return params.wantValid; } "
          + "String s = st.value; String e = et.value; "
          + "if (s.length() != 8 || e.length() != 8) { return params.wantValid; } "
          + "String now = params.nowTime; "
          + "boolean inWindow = (s.compareTo(e) <= 0) "
          + "  ? (now.compareTo(s) >= 0 && now.compareTo(e) <= 0) "
          + "  : (now.compareTo(s) >= 0 || now.compareTo(e) <= 0); "
          + "return params.wantValid ? inWindow : !inWindow;";

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

        List<Query> clauses = new ArrayList<>(2);
        if (request.hasActiveMatch()) {
            clauses.add(activeClause(request.activeMatch()));
        }
        if (request.hasValidMatch()) {
            clauses.add(validityClause(request.validMatch(), now));
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
     * Priority-ordered validity clause: {@code startDate}/{@code endDate} — if either is present
     * — always wins over {@code startTime}/{@code endTime}; the time-of-day window applies only
     * when both date fields are absent; a catalog with nothing usable present counts as valid
     * (for {@code validMatch=true}) and is correctly never selected as out-of-window (for
     * {@code validMatch=false}).
     */
    private static Query validityClause(boolean validMatch, Instant now) {
        final String nowIso = now.toString();
        final String nowTime = now.atZone(ZoneOffset.UTC).toLocalTime().format(HH_MM_SS);

        // True when either raw date field is present (regardless of parseability, mirroring the
        // PostgreSQL "presence, not parseability, decides priority" rule).
        Query hasDateField = Query.of(q -> q.bool(b -> b
                .should(s -> s.exists(e -> e.field(FIELD_VALIDITY_START)))
                .should(s -> s.exists(e -> e.field(FIELD_VALIDITY_END)))
                .minimumShouldMatch("1")));
        Query hasBothTimeFields = Query.of(q -> q.bool(b -> b
                .filter(f -> f.exists(e -> e.field(FIELD_VALIDITY_START_TIME)))
                .filter(f -> f.exists(e -> e.field(FIELD_VALIDITY_END_TIME)))));
        Query timeWindowScript = Query.of(q -> q.script(sq -> sq.script(scr -> scr
                .lang("painless")
                .source(TIME_WINDOW_SCRIPT)
                .params(Map.of(
                        "nowTime", JsonData.of(nowTime),
                        "wantValid", JsonData.of(validMatch))))));

        if (validMatch) {
            Query dateBranch = Query.of(q -> q.bool(b -> b
                    .filter(hasDateField)
                    .filter(dateWindowClause(true, nowIso))));
            Query timeBranch = Query.of(q -> q.bool(b -> b
                    .mustNot(hasDateField)
                    .filter(hasBothTimeFields)
                    .filter(timeWindowScript)));
            Query neitherBranch = Query.of(q -> q.bool(b -> b
                    .mustNot(hasDateField)
                    .mustNot(hasBothTimeFields)));
            return Query.of(q -> q.bool(b -> b
                    .should(dateBranch).should(timeBranch).should(neitherBranch)
                    .minimumShouldMatch("1")));
        }

        Query dateBranch = Query.of(q -> q.bool(b -> b
                .filter(hasDateField)
                .filter(dateWindowClause(false, nowIso))));
        Query timeBranch = Query.of(q -> q.bool(b -> b
                .mustNot(hasDateField)
                .filter(hasBothTimeFields)
                .filter(timeWindowScript)));
        return Query.of(q -> q.bool(b -> b.should(dateBranch).should(timeBranch).minimumShouldMatch("1")));
    }

    /**
     * The original date-only window clause: validMatch=TRUE → within window (absent bounds
     * pass); validMatch=FALSE → outside a present window (absent bounds do not match, so
     * absent/open-ended-inside is excluded).
     */
    private static Query dateWindowClause(boolean validMatch, String nowIso) {
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
