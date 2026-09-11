package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructException.UnsupportedConstruct;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Unit tests for {@link FilterPredicateCompiler}'s own tokenizer/parser — specifically the
 * function-call and descendant-segment detection that must apply to constructs embedded inside
 * a filter predicate's {@code ?(...)} content, not just at the {@code Segment} level (which
 * {@link UnsupportedConstructDetectorTest} already covers).
 */
class FilterPredicateCompilerTest {

    @Test
    @DisplayName("count() used inline inside a filter predicate is flagged as COUNT_FUNCTION, "
            + "never a generic invalid-syntax failure")
    void inlineCountFunction_flaggedAsCountFunction() {
        assertThatThrownBy(() -> compile("count(@) > 0"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.COUNT_FUNCTION));
    }

    @Test
    @DisplayName("value() used inline inside a filter predicate is flagged as VALUE_FUNCTION")
    void inlineValueFunction_flaggedAsValueFunction() {
        assertThatThrownBy(() -> compile("value(@.descriptor.name) == \"Rich Offer 1\""))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.VALUE_FUNCTION));
    }

    @Test
    @DisplayName("match() used inline inside a filter predicate is flagged as REGEX_FUNCTION")
    void inlineMatchFunction_flaggedAsRegexFunction() {
        assertThatThrownBy(() -> compile("match(@.descriptor.name, \".*Rich.*\")"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.REGEX_FUNCTION));
    }

    @Test
    @DisplayName("search() used inline inside a filter predicate is flagged as REGEX_FUNCTION")
    void inlineSearchFunction_flaggedAsRegexFunction() {
        assertThatThrownBy(() -> compile("search(@.descriptor.name, \"abc\")"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.REGEX_FUNCTION));
    }

    @Test
    @DisplayName("length() used inline inside a filter predicate is denylisted consistently with "
            + "the segment-level detector's default bucket (VALUE_FUNCTION), not silently accepted")
    void inlineLengthFunction_flaggedAsValueFunction() {
        assertThatThrownBy(() -> compile("length(@.descriptor.name) > 3"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.VALUE_FUNCTION));
    }

    @Test
    @DisplayName("function call nested inside a boolean combination is still caught, not just at "
            + "the top level of the predicate")
    void inlineFunctionCall_caughtWhenNested() {
        assertThatThrownBy(() -> compile("(@.price < 100 && count(@.offers) > 2)"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.COUNT_FUNCTION));
    }

    @Test
    @DisplayName("descendant segment ('..') embedded in a filter predicate's own path is flagged, "
            + "not silently mis-parsed as empty-named path components")
    void inlineDescendantSegment_flaggedAsDescendantSegment() {
        assertThatThrownBy(() -> compile("@..name == \"x\""))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstruct.DESCENDANT_SEGMENT));
    }

    @Test
    @DisplayName("ordinary comparisons, existence checks, and boolean combinations still compile "
            + "as before — the function-call detection must not weaken supported syntax")
    void supportedPredicates_stillCompile() {
        assertThat(compile("@.price < 100").fragment()).contains("try_to_numeric(");
        assertThat(compile("@.price < 100 && @.currency == 'INR'").fragment()).contains(" AND ");
        assertThat(compile("@.descriptor.name").fragment()).contains("IS NOT NULL");
        assertThat(compile("!(@.price < 100)").fragment()).startsWith("NOT (");
    }

    // ── Hardening fix 2: recursion-depth guard ───────────────────────────────

    @Test
    @DisplayName("deeply-nested parens produce a clean InvalidRfc9535SyntaxException, never a "
            + "StackOverflowError past the max nesting depth")
    void deeplyNestedParens_rejectedCleanly() {
        String pathologicallyNested = "(".repeat(5000) + "@.price < 100" + ")".repeat(5000);

        assertThatThrownBy(() -> compile(pathologicallyNested))
                .isInstanceOf(InvalidRfc9535SyntaxException.class)
                .hasMessageContaining("nesting depth");
    }

    @Test
    @DisplayName("deeply-chained NOT operators produce a clean InvalidRfc9535SyntaxException, "
            + "never a StackOverflowError")
    void deeplyChainedNot_rejectedCleanly() {
        String pathologicalNot = "!".repeat(5000) + "(@.price < 100)";

        assertThatThrownBy(() -> compile(pathologicalNot))
                .isInstanceOf(InvalidRfc9535SyntaxException.class)
                .hasMessageContaining("nesting depth");
    }

    @Test
    @DisplayName("nesting within the supported depth still compiles normally — the guard doesn't "
            + "reject reasonable, real-world predicates")
    void moderateNesting_stillCompiles() {
        String reasonable = "((((@.price < 100))))";
        assertThat(compile(reasonable).fragment()).contains("try_to_numeric(");
    }

    // ── Tokenizer forward-progress guard (infinite-loop DoS fix) ─────────────

    @Test
    @DisplayName("a lone '=' (not part of '==') is rejected with a clean "
            + "InvalidRfc9535SyntaxException instead of hanging the tokenizer forever")
    void loneEqualsSign_rejectedCleanlyNotHung() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> compile("@.a = 1"))
                        .isInstanceOf(InvalidRfc9535SyntaxException.class)
                        .hasMessageContaining("Unexpected character"));
    }

    @Test
    @DisplayName("a lone '&' (not part of '&&') is rejected with a clean "
            + "InvalidRfc9535SyntaxException instead of hanging the tokenizer forever")
    void loneAmpersand_rejectedCleanlyNotHung() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> compile("@.a & 1"))
                        .isInstanceOf(InvalidRfc9535SyntaxException.class)
                        .hasMessageContaining("Unexpected character"));
    }

    @Test
    @DisplayName("a lone '|' (not part of '||') is rejected with a clean "
            + "InvalidRfc9535SyntaxException instead of hanging the tokenizer forever")
    void lonePipe_rejectedCleanlyNotHung() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> compile("@.a | 1"))
                        .isInstanceOf(InvalidRfc9535SyntaxException.class)
                        .hasMessageContaining("Unexpected character"));
    }

    // ── Existence check on non-scalar targets ────────────────────────────────

    @Test
    @DisplayName("existence predicate on a field uses structural '#>' extraction, not text '#>>', "
            + "so it correctly matches non-scalar (array/object) targets too")
    void existencePredicate_usesStructuralExtractionForNonScalarTargets() {
        assertThat(compile("@.offers").fragment())
                .isEqualTo("(e0 #> ?::text[]) IS NOT NULL");
    }

    private static FilterPredicateCompiler.Sql compile(String filterContent) {
        return new FilterPredicateCompiler("e0", filterContent).compile();
    }
}
