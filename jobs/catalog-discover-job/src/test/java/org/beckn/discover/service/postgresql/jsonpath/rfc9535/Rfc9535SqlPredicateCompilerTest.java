package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.jsonpath.JsonPath;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural unit tests for {@link Rfc9535SqlPredicateCompiler}: asserts the shape of the
 * emitted SQL and, critically, that every path-segment name and literal value is a bound
 * parameter rather than SQL text (the injection-safety hard rule from the design doc).
 *
 * <p>Result-set-against-fixture-rows verification (per the design doc's "Test strategy") needs a
 * real Postgres instance and is covered by the Testcontainers-backed integration tests; these
 * unit tests verify the compiler's contract in isolation.</p>
 */
class Rfc9535SqlPredicateCompilerTest {

    private final Rfc9535SqlPredicateCompiler compiler = new Rfc9535SqlPredicateCompiler();

    @Test
    @DisplayName("flat comparison filter compiles to a parameterized EXISTS with typed numeric cast")
    void flatComparisonFilter() {
        CompiledPredicate predicate = compile("$.resources[?(@.rating.ratingValue >= 4.5)]");

        assertThat(predicate.whereFragment()).contains("EXISTS (SELECT 1 FROM jsonb_path_query(");
        assertThat(predicate.whereFragment()).contains("try_to_numeric(");
        assertThat(predicate.whereFragment()).doesNotContain("4.5"); // literal never concatenated
        // [0]=outer array path, [1]=field-extraction path array, [2]=numeric literal
        assertThat(predicate.whereParameters()).hasSize(3);
        assertThat(predicate.whereParameters().get(0)).isEqualTo(new String[]{"resources"});
        assertThat(predicate.whereParameters().get(1)).isEqualTo(new String[]{"rating", "ratingValue"});
        assertThat(predicate.whereParameters().get(2)).isEqualTo(new java.math.BigDecimal("4.5"));
        assertThat(predicate.offersProjectionFragment()).isEmpty();
    }

    @Test
    @DisplayName("nested path anchored under offers produces an offers-projection fragment")
    void nestedOffersFilter_producesProjection() {
        CompiledPredicate predicate = compile("$.catalogs[0].offers[?(@.price < 100)]");

        assertThat(predicate.offersProjectionFragment()).isPresent();
        assertThat(predicate.offersProjectionFragment().get())
                .contains("jsonb_agg(po)")
                .contains("WHERE");
        assertThat(predicate.projectionParameters()).hasSize(3);
        assertThat(predicate.projectionParameters().get(0))
                .isEqualTo(new String[]{"catalogs", "0", "offers"});
    }

    @Test
    @DisplayName("filter anchored on resources (not offers) produces no projection fragment")
    void resourceLevelFilter_noProjection() {
        CompiledPredicate predicate = compile("$.resources[?(@.rating.ratingValue >= 4.5)]");
        assertThat(predicate.offersProjectionFragment()).isEmpty();
        assertThat(predicate.projectionParameters()).isEmpty();
    }

    @Test
    @DisplayName("equality operator '==' compiles to valid Postgres '=' syntax, never the literal '=='")
    void equalityOperator_compilesToPostgresEquals() {
        CompiledPredicate predicate = compile("$.offers[?(@.id == 'offer-1')]");

        assertThat(predicate.whereFragment()).contains("::text = ?");
        assertThat(predicate.whereFragment()).doesNotContain("==");
    }

    @Test
    @DisplayName("logical && combination compiles both comparisons AND'd together, still parameterized")
    void logicalAndCombination() {
        CompiledPredicate predicate = compile("$.offers[?(@.price < 100 && @.currency == 'INR')]");

        assertThat(predicate.whereFragment()).contains(" AND ");
        assertThat(predicate.whereFragment()).doesNotContain("100").doesNotContain("INR");
        // outer array path + (field path + literal) * 2 comparisons = 5 bound values
        assertThat(predicate.whereParameters()).hasSize(5);
    }

    @Test
    @DisplayName("hostile member name (embedded quote/braces/comma) is bound as a parameter, never concatenated")
    void hostileMemberName_isBoundNotConcatenated() {
        // Double-quoted so the embedded single quote needs no escaping — still exercises the
        // hard rule: braces/comma/quote characters must never be concatenated into SQL text.
        String hostileName = "a'{},b";
        CompiledPredicate predicate = compile("$.offers[?(@[\"" + hostileName + "\"] < 100)]");

        // The hostile name must appear only as a bound parameter value, never inside the SQL text
        // (which would indicate string concatenation and a live injection vector).
        assertThat(predicate.whereFragment()).doesNotContain(hostileName);
        boolean hostileNameIsBound = predicate.whereParameters().stream()
                .filter(p -> p instanceof String[])
                .map(p -> (String[]) p)
                .anyMatch(arr -> java.util.Arrays.asList(arr).contains(hostileName));
        assertThat(hostileNameIsBound).isTrue();
    }

    @Test
    @DisplayName("wildcard terminal compiles to a non-empty-array EXISTS")
    void wildcardTerminal() {
        CompiledPredicate predicate = compile("$.resources[*]");
        assertThat(predicate.whereFragment()).isEqualTo(
                "EXISTS (SELECT 1 FROM jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0)");
        assertThat(predicate.whereParameters()).containsExactly((Object) new String[]{"resources"});
    }

    @Test
    @DisplayName("index [n] terminal compiles to an ordinality-filtered existence check")
    void positiveIndexTerminal() {
        CompiledPredicate predicate = compile("$.offers[0]");
        assertThat(predicate.whereFragment()).contains("WITH ORDINALITY e0(value, ord)");
        assertThat(predicate.whereFragment()).contains("e0.ord - 1 = 0");
    }

    @Test
    @DisplayName("negative index [-1] and [last] both reference jsonb_array_length, never a literal offset")
    void negativeAndLastIndex_useComputedOffset() {
        CompiledPredicate negative = compile("$.offers[-1]");
        CompiledPredicate last = compile("$.offers[last]");

        assertThat(negative.whereFragment()).contains("jsonb_array_length(").contains("+ (-1)");
        assertThat(last.whereFragment()).contains("jsonb_array_length(").contains("+ (-1)");
    }

    @Test
    @DisplayName("descendant segment is not compiled here — the denylist is the caller's job")
    void descendantSegment_isNotThisCompilersConcern() {
        // Rfc9535SqlPredicateCompiler is only invoked once the denylist has already cleared the
        // segment list (see Rfc9535FilterCompiler); classification of ".." itself is exercised
        // in UnsupportedConstructDetectorTest.
        assertThatThrownBy(() -> compile("$..offers[?(@.price<1)]"))
                .isInstanceOfAny(InvalidRfc9535SyntaxException.class, UnsupportedConstructException.class);
    }

    @Test
    @DisplayName("slice-with-step is rejected by this compiler as unsupported")
    void sliceWithStep_throwsUnsupported() {
        assertThatThrownBy(() -> compile("$.offers[0:5:2]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.SLICE_WITH_STEP));
    }

    @Test
    @DisplayName("plain two-part slice compiles to a bounded ordinality EXISTS")
    void plainSlice_compiles() {
        CompiledPredicate predicate = compile("$.offers[1:3]");
        assertThat(predicate.whereFragment()).contains("e0.ord - 1 >= 1").contains("e0.ord - 1 < 3");
    }

    @Test
    @DisplayName("wildcard followed by a further filter (design-doc spike Case 2 shape) is no "
            + "longer forced-terminal — it opens a correlated EXISTS level and recursion continues")
    void wildcardThenFilter_opensNestedCorrelatedLevel() {
        CompiledPredicate predicate = compile("$.resources[*].offers[?(@.validity.endDate >= \"2025-01-01T00:00:00Z\")]");

        // Two array-producing levels: e0 for resources[*], e1 for the offers filter — the inner
        // level is correlated against e0 (the resources[*] element), not back to
        // i.payload directly.
        assertThat(predicate.whereFragment())
                .contains("jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0")
                .contains("jsonb_path_query(e0 #> ?::text[], '$.**{1}') e1")
                .contains("try_to_timestamptz(");
        assertThat(predicate.whereFragment()).doesNotContain("2025-01-01");

        // Offers-projection still triggers, anchored at the nested "offers" level, and is
        // correlated the same way (po element sourced from e0's offers, not the root payload).
        assertThat(predicate.offersProjectionFragment()).isPresent();
        assertThat(predicate.offersProjectionFragment().get())
                .contains("jsonb_agg(po)")
                .contains("jsonb_path_query(i.payload #> ?::text[], '$.**{1}') p0")
                .contains("jsonb_path_query(p0 #> ?::text[], '$.**{1}') po");
    }

    @Test
    @DisplayName("3-level-deep chain (catalogs[*].resources[*].offers[?(...)]) proves the recursion "
            + "generalizes beyond a single nested level")
    void threeLevelChain_generalizesRecursion() {
        CompiledPredicate predicate = compile("$.catalogs[*].resources[*].offers[?(@.price < 100)]");

        assertThat(predicate.whereFragment())
                .contains("jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0")
                .contains("jsonb_path_query(e0 #> ?::text[], '$.**{1}') e1")
                .contains("jsonb_path_query(e1 #> ?::text[], '$.**{1}') e2")
                .contains("try_to_numeric(");
        // [0]=catalogs path, [1]=resources path (relative to e0), [2]=offers path (relative to
        // e1), [3]=field-extraction path (relative to e2), [4]=numeric literal
        assertThat(predicate.whereParameters()).hasSize(5);
        assertThat(predicate.whereParameters().get(0)).isEqualTo(new String[]{"catalogs"});
        assertThat(predicate.whereParameters().get(1)).isEqualTo(new String[]{"resources"});
        assertThat(predicate.whereParameters().get(2)).isEqualTo(new String[]{"offers"});
        assertThat(predicate.whereParameters().get(3)).isEqualTo(new String[]{"price"});
    }

    @Test
    @DisplayName("mid-path wildcard/filter/slice/last chaining shapes are never misreported as "
            + "invalid syntax — the denylist is reserved for genuinely unsupported constructs")
    void nestedChainingShapes_neverMisclassifiedAsInvalidSyntax() {
        // Regression for the prior pass's bug: chaining after a terminal used to throw
        // InvalidRfc9535SyntaxException ("invalid syntax") for expressions that are actually
        // valid, supported RFC 9535 syntax — misrepresenting a valid client request as malformed.
        assertThat(compile("$.resources[*].offers[?(@.price < 100)]")).isNotNull();
        assertThat(compile("$.resources[0].offers[?(@.price < 100)]")).isNotNull();
        assertThat(compile("$.resources[1:3].offers[?(@.price < 100)]")).isNotNull();
        assertThat(compile("$.resources[last].offers[?(@.price < 100)]")).isNotNull();
    }

    @Test
    @DisplayName("function call embedded inside a filter predicate ('?(count(@) > 0)') is flagged "
            + "with the specific unsupported construct, never a generic invalid-syntax failure — "
            + "regression for the bug where these fell through to SCH_INVALID_JSONPATH")
    void inlineFunctionCallInFilterPredicate_flaggedWithSpecificConstruct() {
        assertThatThrownBy(() -> compile("$.catalogs[0].offers[?(count(@) > 0)]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.COUNT_FUNCTION));

        assertThatThrownBy(() -> compile(
                "$.catalogs[0].offers[?(value(@.descriptor.name) == \"Rich Offer 1\")]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.VALUE_FUNCTION));

        assertThatThrownBy(() -> compile(
                "$.catalogs[0].offers[?(match(@.descriptor.name, \".*Rich.*\"))]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.REGEX_FUNCTION));
    }

    @Test
    @DisplayName("filter after a wildcard ([*][?(...)]) compiles: the filter level iterates the children "
            + "of each element (RFC 9535 member values for objects), not jsonb_array_elements")
    void filterAfterWildcard_iteratesChildrenOfEachElement() {
        CompiledPredicate predicate = compile(
                "$.catalogs[*].resources[*][?(@.resourceAttributes.connectorType == 'CCS2')]");

        assertThat(predicate.whereFragment())
                .contains("jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0")
                .contains("jsonb_path_query(e0 #> ?::text[], '$.**{1}') e1")
                .contains("jsonb_path_query(e1 #> ?::text[], '$.**{1}') e2")
                .contains("(e2 #>> ?::text[])::text = ?");
        // the filter level sits directly on the wildcard element: empty path array
        assertThat(predicate.whereParameters().get(2)).isEqualTo(new String[]{});
    }

    @Test
    @DisplayName("root filter ($[?(...)], the LTS Intent.filters example shape) compiles against the root's children")
    void rootFilter_iteratesChildrenOfRoot() {
        CompiledPredicate predicate = compile("$[?(@.resourceAttributes.connectorType == 'CCS2')]");

        assertThat(predicate.whereFragment())
                .startsWith("EXISTS (SELECT 1 FROM jsonb_path_query(i.payload #> ?::text[], '$.**{1}') e0 WHERE ");
        assertThat(predicate.whereParameters().get(0)).isEqualTo(new String[]{});
    }

    @Test
    @DisplayName("index / last / slice levels read array elements via strict $[*] with silent errors "
            + "(no match on an object or primitive, never a statement abort)")
    void ordinalLevels_useSilentStrictArrayElements() {
        for (String expression : List.of("$.offers[0]", "$.offers[-1]", "$.offers[last]", "$.offers[1:3]",
                "$.resources[last].offers[?(@.price < 100)]")) {
            assertThat(compile(expression).whereFragment())
                    .as(expression)
                    .contains("jsonb_path_query(i.payload #> ?::text[], 'strict $[*]', '{}', true) "
                            + "WITH ORDINALITY e0(value, ord)");
        }
    }

    @Test
    @DisplayName("no compiled level ever calls jsonb_array_elements (it aborts the statement on an object)")
    void noLevelUsesJsonbArrayElements() {
        for (String expression : List.of(
                "$.catalogs[*].resources[?(@.resourceAttributes.connectorType == 'CCS2')]",
                "$.catalogs[?(@.provider.id == 'ecopower-charging')]",
                "$.catalogs[*].offers[?(@.validity.startTime <= '07:00:00')]",
                "$.catalogs[*].resources[*][?(@.id == 'x')]",
                "$.catalogs[*][?(@.provider.id == 'x')]",
                "$[?(@.x == 1)]",
                "$.catalogs[0].offers[1:3]",
                "$.catalogs[*].resources[*].offers[-1]")) {
            CompiledPredicate predicate = compile(expression);
            assertThat(predicate.whereFragment()).as(expression).doesNotContain("jsonb_array_elements");
            predicate.offersProjectionFragment().ifPresent(projection ->
                    assertThat(projection).as(expression).doesNotContain("jsonb_array_elements"));
        }
    }

    @Test
    @DisplayName("chained filters: all FROM-item parameters bind before any WHERE parameter, in SQL text order")
    void chainedFilters_parametersFollowSqlTextOrder() {
        CompiledPredicate predicate = compile(
                "$.catalogs[?(@.provider.id == 'x')].resources[?(@.resourceAttributes.connectorType == 'CCS2')]");

        assertThat(predicate.whereFragment()).matches(
                "EXISTS \\(SELECT 1 FROM jsonb_path_query\\(i\\.payload #> \\?::text\\[\\], '\\$\\.\\*\\*\\{1}'\\) e0, "
                        + "jsonb_path_query\\(e0 #> \\?::text\\[\\], '\\$\\.\\*\\*\\{1}'\\) e1 WHERE .*");
        assertThat(predicate.whereParameters()).containsExactly(
                new String[]{"catalogs"}, new String[]{"resources"},
                new String[]{"provider", "id"}, "x",
                new String[]{"resourceAttributes", "connectorType"}, "CCS2");
    }

    @Test
    @DisplayName("every '?::text[]' placeholder binds a path array and every other '?' a literal — "
            + "for chained filters, mid-path [last]/[-k]/[a:] and the offers projection")
    void placeholdersAlignWithParameters() {
        for (String expression : List.of(
                "$.catalogs[?(@.provider.id == 'x')].resources[?(@.resourceAttributes.maxPowerKW >= 50)]",
                "$.catalogs[?(@.id == 'c')].offers[?(@.price < 100)]",
                "$.catalogs[0].resources[last].offers[?(@.price < 40)]",
                "$.catalogs[0].resources[-2].offers[?(@.price < 40)]",
                "$.catalogs[0].resources[1:].offers[?(@.price < 40)]",
                "$.catalogs[0].resources[-3:-1].offers[*]",
                "$.catalogs[*].resources[?(@.id == 'r')].offers[-1]",
                "$.catalogs[*].resources[*][?(@.id == 'x')]")) {
            CompiledPredicate predicate = compile(expression);
            assertPlaceholdersAligned(expression, predicate.whereFragment(), predicate.whereParameters());
            predicate.offersProjectionFragment().ifPresent(projection ->
                    assertPlaceholdersAligned(expression + " (projection)", projection,
                            predicate.projectionParameters()));
        }
    }

    private static void assertPlaceholdersAligned(String label, String sql, List<Object> params) {
        java.util.regex.Matcher placeholder = java.util.regex.Pattern.compile("\\?(::text\\[])?").matcher(sql);
        int index = 0;
        while (placeholder.find()) {
            assertThat(index).as("%s: more placeholders than parameters", label).isLessThan(params.size());
            boolean pathSlot = placeholder.group(1) != null;
            Object bound = params.get(index);
            assertThat(bound instanceof String[])
                    .as("%s: placeholder #%d is a %s slot but is bound to %s", label, index,
                            pathSlot ? "path-array" : "literal",
                            bound instanceof String[] arr ? java.util.Arrays.toString(arr) : bound)
                    .isEqualTo(pathSlot);
            index++;
        }
        assertThat(index).as("%s: placeholder count", label).isEqualTo(params.size());
    }

    private CompiledPredicate compile(String expression) {
        return compiler.toSqlPredicate(JsonPath.parse(expression).getSegments());
    }
}
