package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import org.noear.snack4.jsonpath.segment.DescendantSegment;
import org.noear.snack4.jsonpath.segment.FuncSegment;
import org.noear.snack4.jsonpath.segment.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.beckn.discover.service.postgresql.jsonpath.rfc9535.BracketSelectorClassifier.Kind;
import static org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructException.UnsupportedConstruct;

/**
 * Walks an RFC 9535 segment list and emits a typed, parameterized SQL predicate over
 * {@code i.payload}, using plain {@code jsonb} operators (never Postgres's {@code jsonpath}
 * dialect) — see docs/design/DESIGN-rfc9535-jsonpath-grammar.md "DECISION — pure T2".
 *
 * <p><b>Supported shape:</b> any chain of name/index navigation and array-producing selectors
 * (wildcard {@code [*]}, filter {@code ?(...)}, plain slice {@code [a:b]}, index/{@code last}/
 * negative-index) at arbitrary nesting depth — e.g. {@code $.resources[*].offers[?(@.price <
 * 100)]} ("does any resource's offers satisfy X") or {@code $.catalogs[*].resources[*]
 * .offers[?(...)]}. Every array-producing selector that is followed by further segments
 * contributes a correlated {@code jsonb_array_elements(...)} level (aliased {@code e0}, {@code
 * e1}, ...) that the remaining segments are compiled against — not a forced-terminal
 * restriction. Only a genuinely terminal segment (the last segment overall) resolves to the
 * final boolean condition (or, for a terminal filter anchored under {@code offers}, the
 * offers-projection fragment); every intermediate array-producing selector narrows which
 * elements the chain continues into (wildcard: all elements; filter: elements matching the
 * predicate; index/{@code last}/slice: the selected element(s)). All the levels for one compiled
 * expression are emitted as sibling {@code FROM}-list items inside a single {@code EXISTS(...)},
 * which is semantically equivalent to (and simpler than) a chain of nested {@code EXISTS}
 * blocks, since every level is a existential/narrowing quantifier ANDed together.</p>
 *
 * <p>Every path-segment name and literal value is bound as a {@code ?} SQL parameter — see the
 * hard rule in the design doc's "Translation mapping table" and {@link FilterPredicateCompiler}.</p>
 */
@Component
public class Rfc9535SqlPredicateCompiler {

    /**
     * Defensive cap on the total number of segments processed for one compiled expression — see
     * design doc risk 9. Since every nested array-level ({@code EXISTS}/{@code
     * jsonb_array_elements}) is opened by processing one segment, this single counter bounds both
     * flat path-component accumulation and nested-level depth, so a pathological/adversarial
     * expression is rejected rather than generating unbounded SQL.
     */
    private static final int MAX_PATH_DEPTH = 8;

    private static final String BASE_ALIAS = "i.payload";
    /** Path components that anchor a filter's offers-projection trigger (see design doc). */
    private static final String OFFERS_PATH_COMPONENT = "offers";
    private static final String WHERE_ELEMENT_ALIAS_PREFIX = "e";
    private static final String PROJECTION_ELEMENT_ALIAS_PREFIX = "p";
    private static final String PROJECTION_FINAL_ALIAS = "po";

    private final BracketSelectorClassifier classifier = new BracketSelectorClassifier();

    /**
     * Compiles the supported RFC 9535 subset into a {@link CompiledPredicate}.
     *
     * @throws UnsupportedConstructException for any denylisted construct (see
     *                                        {@link UnsupportedConstructDetector})
     */
    public CompiledPredicate toSqlPredicate(List<Segment> segments) {
        LevelPlan wherePlan = buildLevelPlan(segments, WHERE_ELEMENT_ALIAS_PREFIX, null);

        Optional<String> projectionFragment = Optional.empty();
        List<Object> projectionParams = List.of();
        if (wherePlan.terminalKind() == Kind.FILTER && wherePlan.anchoredUnderOffers()) {
            LevelPlan projectionPlan =
                    buildLevelPlan(segments, PROJECTION_ELEMENT_ALIAS_PREFIX, PROJECTION_FINAL_ALIAS);
            projectionFragment = Optional.of(projectionPlan.toProjectionFragment());
            projectionParams = projectionPlan.params();
        }

        return new CompiledPredicate(wherePlan.toExistsFragment(), wherePlan.params(), projectionFragment,
                projectionParams, GrammarPath.RFC_9535);
    }

    /**
     * Walks {@code segments} once, accumulating one {@code FROM}-list item (plus optional
     * {@code WHERE} condition) per array-producing selector, and one flat {@code text[]} path
     * array of accumulated name/index components between array-producing selectors.
     *
     * @param aliasPrefix        alias prefix for each opened level (e.g. {@code "e"} → {@code
     *                           e0}, {@code e1}, ...)
     * @param finalAliasOverride when non-null, used as the alias for the last-opened level
     *                           instead of {@code aliasPrefix + depth} (the offers-projection
     *                           pass uses {@code "po"} so the produced SQL reads naturally as
     *                           {@code jsonb_agg(po)})
     */
    private LevelPlan buildLevelPlan(List<Segment> segments, String aliasPrefix, String finalAliasOverride) {
        List<String> fromClauses = new ArrayList<>();
        List<String> whereConditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<String> pathComponents = new ArrayList<>();
        String currentAlias = BASE_ALIAS;
        int depth = 0;
        int depthUnits = 0;
        boolean anchoredUnderOffers = false;
        Kind terminalKind = null;
        String finalElementAlias = null;

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);

            // Defense in depth: this compiler must reject descendant segments and function
            // segments on its own, even though production callers always run
            // UnsupportedConstructDetector first (see Rfc9535FilterCompiler) — a caller that
            // skips the detector must never get a silently-wrong compiled predicate.
            if (segment instanceof DescendantSegment) {
                throw new UnsupportedConstructException(UnsupportedConstruct.DESCENDANT_SEGMENT,
                        "Descendant segment ('..') is not supported");
            }
            if (segment instanceof FuncSegment) {
                throw new UnsupportedConstructException(UnsupportedConstruct.VALUE_FUNCTION,
                        "Function segment ('" + segment.getOriginalText() + "') is not supported");
            }
            if (++depthUnits > MAX_PATH_DEPTH) {
                throw new InvalidRfc9535SyntaxException(
                        "Filter path exceeds max supported depth (" + MAX_PATH_DEPTH + ")", null);
            }

            BracketSelectorClassifier.Classification classification =
                    classifier.classify(segment.getOriginalText());
            boolean isLastSegment = i == segments.size() - 1;

            if (classification.kind() == Kind.SLICE_WITH_STEP) {
                throw new UnsupportedConstructException(UnsupportedConstruct.SLICE_WITH_STEP,
                        "Array slice with step ('" + classification.content() + "') is not supported");
            }
            if (classification.kind() == Kind.NAME) {
                pathComponents.add(classification.content());
                if (classification.content().equals(OFFERS_PATH_COMPONENT)) {
                    anchoredUnderOffers = true;
                }
                continue;
            }
            // Postgres's #>/#>> path arrays accept a plain (even negative) integer text
            // component directly, so a mid-path index is just another path component, not a
            // level of its own — only a terminal index needs the ordinality machinery below.
            if (classification.kind() == Kind.INDEX && !isLastSegment) {
                pathComponents.add(classification.content());
                continue;
            }

            // From here: an array-producing selector — wildcard, filter, slice, `last`, or a
            // terminal index — that opens a new correlated jsonb_array_elements(...) level.
            String elementAlias = (isLastSegment && finalAliasOverride != null)
                    ? finalAliasOverride
                    : aliasPrefix + depth;
            String[] pathArray = pathComponents.toArray(String[]::new);
            StringBuilder arrayExpr = new StringBuilder();
            appendArrayExprText(arrayExpr, params, currentAlias, pathArray);

            boolean usesOrdinality = classification.kind() == Kind.INDEX
                    || classification.kind() == Kind.LAST
                    || classification.kind() == Kind.SLICE;

            if (usesOrdinality) {
                fromClauses.add("jsonb_array_elements(" + arrayExpr + ") WITH ORDINALITY "
                        + elementAlias + "(value, ord)");
                appendOrdinalCondition(classification, currentAlias, pathArray, elementAlias, params,
                        whereConditions);
                currentAlias = elementAlias + ".value";
            } else {
                fromClauses.add("jsonb_array_elements(" + arrayExpr + ") " + elementAlias);
                currentAlias = elementAlias;
                if (classification.kind() == Kind.FILTER) {
                    FilterPredicateCompiler.Sql predicate =
                            new FilterPredicateCompiler(elementAlias, classification.content()).compile();
                    whereConditions.add(predicate.fragment());
                    params.addAll(predicate.parameters());
                }
            }

            pathComponents = new ArrayList<>();
            depth++;

            if (isLastSegment) {
                terminalKind = classification.kind();
                finalElementAlias = elementAlias;
            }
        }

        if (terminalKind == null) {
            throw new InvalidRfc9535SyntaxException("Expression has no filterable terminal selector", null);
        }
        return new LevelPlan(fromClauses, whereConditions, params, terminalKind, anchoredUnderOffers,
                finalElementAlias);
    }

    /**
     * Index/{@code last}/slice ordinal conditions, compiled via {@code WITH ORDINALITY} against a
     * computed offset — never Postgres's {@code jsonpath} {@code .datetime()}/{@code @@} dialect.
     * Sound for the full RFC 9535 soundness matrix: an empty array, a single-element array, an
     * out-of-range negative index, and {@code last} on an empty array all yield "no match"
     * (false), never a SQL error or a wraparound match, because the offset is clamped against the
     * actual {@code jsonb_array_length} rather than assumed non-negative.
     */
    private static void appendOrdinalCondition(BracketSelectorClassifier.Classification classification,
                                                String currentAlias, String[] pathArray, String elementAlias,
                                                List<Object> params, List<String> whereConditions) {
        switch (classification.kind()) {
            case INDEX, LAST -> {
                int index = classification.kind() == Kind.LAST ? -1 : Integer.parseInt(classification.content());
                StringBuilder condition = new StringBuilder(elementAlias + ".ord - 1 = ");
                if (index >= 0) {
                    condition.append(index);
                } else {
                    appendArrayLength(condition, params, currentAlias, pathArray);
                    condition.append(" + (").append(index).append(")");
                }
                whereConditions.add(condition.toString());
            }
            case SLICE -> {
                String[] parts = classification.content().split(":", 2);
                String startClause = parts.length > 0 && !parts[0].isBlank() ? parts[0].trim() : "0";
                String endClause = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;

                StringBuilder startCondition = new StringBuilder(elementAlias + ".ord - 1 >= ");
                appendSliceBound(startCondition, params, currentAlias, pathArray, startClause);
                whereConditions.add(startCondition.toString());

                StringBuilder endCondition = new StringBuilder(elementAlias + ".ord - 1 < ");
                if (endClause != null) {
                    appendSliceBound(endCondition, params, currentAlias, pathArray, endClause);
                } else {
                    appendArrayLength(endCondition, params, currentAlias, pathArray);
                }
                whereConditions.add(endCondition.toString());
            }
            default -> throw new IllegalStateException("Unreachable: " + classification.kind());
        }
    }

    private static void appendArrayExprText(StringBuilder sql, List<Object> params, String currentAlias,
                                             String[] pathArray) {
        sql.append(currentAlias).append(" #> ?::text[]");
        params.add(pathArray);
    }

    private static void appendArrayLength(StringBuilder sql, List<Object> params, String currentAlias,
                                           String[] pathArray) {
        sql.append("jsonb_array_length(");
        appendArrayExprText(sql, params, currentAlias, pathArray);
        sql.append(")");
    }

    /**
     * Every appearance of the path-array placeholder is pushed to {@code params} at the exact
     * point it's appended to the SQL text, so placeholder count always matches bound-parameter
     * count regardless of how many times a negative bound requires re-referencing {@code
     * jsonb_array_length(...)}.
     */
    private static void appendSliceBound(StringBuilder sql, List<Object> params, String currentAlias,
                                          String[] pathArray, String bound) {
        try {
            int value = Integer.parseInt(bound);
            if (value >= 0) {
                sql.append(value);
            } else {
                appendArrayLength(sql, params, currentAlias, pathArray);
                sql.append(" + (").append(value).append(")");
            }
        } catch (NumberFormatException e) {
            throw new InvalidRfc9535SyntaxException("Invalid slice bound: " + bound, e);
        }
    }

    /**
     * One compiled walk of the segment list: the {@code FROM}-list items and {@code WHERE}
     * conditions needed to express "does there exist a path through every opened array level
     * satisfying every level's condition" — used both for the boolean {@code EXISTS} predicate
     * and, when the terminal filter is anchored under {@code offers}, for the offers-projection
     * fragment (a structurally identical query, just {@code SELECT jsonb_agg(...)} instead of
     * {@code SELECT 1}).
     */
    private record LevelPlan(List<String> fromClauses, List<String> whereConditions, List<Object> params,
                              Kind terminalKind, boolean anchoredUnderOffers, String finalElementAlias) {

        String toExistsFragment() {
            return "EXISTS (SELECT 1 FROM " + String.join(", ", fromClauses) + whereClause() + ")";
        }

        String toProjectionFragment() {
            return "SELECT jsonb_agg(" + finalElementAlias + ") FROM " + String.join(", ", fromClauses)
                    + whereClause();
        }

        private String whereClause() {
            return whereConditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereConditions);
        }
    }
}
