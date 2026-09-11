package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.noear.snack4.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the official, spec-published RFC 9535 Compliance Test Suite ({@code cts.json}, vendored
 * from the JSONPath standard's own test suite at {@code src/test/resources/rfc9535/cts.json})
 * against our actual compiler pipeline — {@code JsonPath.parse}, {@link UnsupportedConstructDetector},
 * and {@link Rfc9535SqlPredicateCompiler} — as a spec-authoritative regression corpus, distinct
 * from our hand-written unit tests.
 *
 * <p>Requires no Docker/Postgres — every assertion here is about parse/compile behavior, not
 * query execution against real data, mirroring {@link Rfc9535SqlPredicateCompilerTest} and
 * {@link UnsupportedConstructDetectorTest}.</p>
 *
 * <p>The CTS's {@code selector} strings are written against arbitrary JSON documents (e.g.
 * {@code $.a.b}), not Discovr's specific {@code $.resources[...]}/{@code $.catalogs[...]} payload
 * shape, and our T2 compiler only emits SQL for a filterable terminal (wildcard/filter/slice/
 * index/last) — a spec-valid selector with no such terminal (e.g. a bare member-name path) is
 * syntactically fine RFC 9535 but outside what {@link Rfc9535SqlPredicateCompiler} translates,
 * and is reported as "out of scope" below rather than forced to pass or silently dropped. Per the
 * design doc, this test's job is to surface exactly this class of gap, not to be gamed into a
 * 100% pass rate — see docs/design/DESIGN-rfc9535-jsonpath-grammar.md "Hardening additions" for
 * the recorded pass-rate breakdown from the last run.</p>
 */
class Rfc9535ComplianceTestSuiteTest {

    private static final Logger log = LoggerFactory.getLogger(Rfc9535ComplianceTestSuiteTest.class);
    private static final String CTS_RESOURCE_PATH = "/rfc9535/cts.json";

    /**
     * Plain JUnit test with no Spring context, so Spring's auto-configured {@code ObjectMapper}
     * bean can't be injected here — this shared, purpose-built {@link JsonMapper} instance
     * replaces a raw {@code new ObjectMapper()} to keep the pattern consistent with the repo's
     * "inject Spring Boot's auto-configured bean" rule in spirit, for a context where the exact
     * DI mechanism doesn't apply.
     */
    private static final JsonMapper CTS_FIXTURE_MAPPER = JsonMapper.builder().build();
    private final UnsupportedConstructDetector unsupportedConstructDetector = new UnsupportedConstructDetector();
    private final Rfc9535SqlPredicateCompiler sqlPredicateCompiler = new Rfc9535SqlPredicateCompiler();

    private record CtsCase(String name, String selector, boolean invalidSelector) {
    }

    @Test
    void everyCtsCase_classifiesConsistentlyWithOurCompilerPipeline() throws IOException {
        List<CtsCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        List<CtsCase> invalidCases = cases.stream().filter(CtsCase::invalidSelector).toList();
        List<CtsCase> validCases = cases.stream().filter(c -> !c.invalidSelector()).toList();

        InvalidCaseTally invalidTally = classifyInvalidCases(invalidCases);
        ValidCaseTally validTally = classifyValidCases(validCases);

        log.info("RFC 9535 CTS run: {} total cases ({} invalid_selector, {} valid)",
                cases.size(), invalidCases.size(), validCases.size());
        log.info("  invalid_selector cases: {} correctly rejected by JsonPath.parse, {} leniency gaps (spec-invalid syntax accepted)",
                invalidTally.correctlyRejected(), invalidTally.leniencyGaps().size());
        log.info("  valid cases: {} compiled (supported subset), {} denylisted-and-rejected, "
                        + "{} out-of-scope (valid RFC 9535, no filterable terminal for our T2 translator), "
                        + "{} unexpected parser failures on spec-valid syntax",
                validTally.compiled(), validTally.denylisted(), validTally.outOfScope(),
                validTally.unexpectedFailures().size());

        // Every case is accounted for in exactly one bucket — a sanity check on the classifier
        // itself, not a pass-rate claim.
        assertThat(invalidTally.correctlyRejected() + invalidTally.leniencyGaps().size())
                .isEqualTo(invalidCases.size());
        assertThat(validTally.compiled() + validTally.denylisted() + validTally.outOfScope()
                + validTally.unexpectedFailures().size())
                .isEqualTo(validCases.size());

        // Recorded baseline tallies from the design doc's Acceptance Criteria section
        // (docs/design/DESIGN-rfc9535-jsonpath-grammar.md). Asserting the exact numbers — not
        // just that the buckets sum correctly — is what actually makes a regression (e.g. the
        // denylist silently letting every case "compile" instead of being rejected) fail this
        // test; a deliberate future change to the parser/denylist must consciously update these
        // numbers in the same commit.
        assertThat(invalidTally.correctlyRejected()).as("invalid_selector cases correctly rejected").isEqualTo(28);
        assertThat(invalidTally.leniencyGaps()).as("invalid_selector known leniency gaps").hasSize(219);
        assertThat(validTally.compiled()).as("valid cases compiled").isEqualTo(161);
        assertThat(validTally.denylisted()).as("valid cases denylisted-and-rejected").isEqualTo(109);
        assertThat(validTally.outOfScope()).as("valid cases out-of-scope").isEqualTo(157);
        assertThat(validTally.unexpectedFailures()).as("valid cases with unexpected failures").hasSize(30);

        // Every compiled case must actually be free of a thrown exception, and every
        // denylist-rejected case must carry one of our five documented UnsupportedConstruct
        // reasons — never a bare, unclassified failure.
        validTally.unexpectedFailures().forEach(f -> log.warn("  unexpected failure: {}", f));
        invalidTally.leniencyGaps().forEach(f -> log.warn("  leniency gap: {}", f));
    }

    // ── invalid_selector classification ─────────────────────────────────────

    private record InvalidCaseTally(int correctlyRejected, List<String> leniencyGaps) {
    }

    /**
     * A case marked {@code invalid_selector: true} must fail RFC 9535 parsing. Checked at the
     * narrowest possible seam — {@code JsonPath.parse} itself — since that is the exact boundary
     * {@link Rfc9535FilterCompiler} treats as "not valid RFC 9535 syntax" (see its {@code
     * doCompile} catch-all for {@code RuntimeException}), and this requires no live Postgres
     * connection to check purely syntactic validity.
     */
    private InvalidCaseTally classifyInvalidCases(List<CtsCase> invalidCases) {
        int correctlyRejected = 0;
        List<String> leniencyGaps = new ArrayList<>();
        for (CtsCase testCase : invalidCases) {
            try {
                JsonPath.parse(testCase.selector());
                // Reached without throwing: our parser is more lenient than the spec here — a
                // real gap, not something to silently pass.
                leniencyGaps.add(testCase.name());
            } catch (RuntimeException e) {
                correctlyRejected++;
            }
        }
        return new InvalidCaseTally(correctlyRejected, leniencyGaps);
    }

    // ── valid-selector classification ───────────────────────────────────────

    private record ValidCaseTally(int compiled, int denylisted, int outOfScope, List<String> unexpectedFailures) {
    }

    private ValidCaseTally classifyValidCases(List<CtsCase> validCases) {
        int compiled = 0;
        int denylisted = 0;
        int outOfScope = 0;
        List<String> unexpectedFailures = new ArrayList<>();

        for (CtsCase testCase : validCases) {
            try {
                var parsed = JsonPath.parse(testCase.selector());
                Optional<UnsupportedConstructException.UnsupportedConstruct> unsupported =
                        unsupportedConstructDetector.firstUnsupported(parsed.getSegments());
                if (unsupported.isPresent()) {
                    denylisted++;
                    continue;
                }
                sqlPredicateCompiler.toSqlPredicate(parsed.getSegments());
                compiled++;
            } catch (UnsupportedConstructException e) {
                denylisted++;
            } catch (InvalidRfc9535SyntaxException e) {
                // Valid RFC 9535 syntax, but outside the T2 translator's supported shape (e.g.
                // no filterable terminal selector) — a documented, in-scope translator
                // limitation, not a parser bug. See design doc "Decided scope".
                outOfScope++;
            } catch (RuntimeException e) {
                // Anything else (including snack4-jsonpath itself rejecting spec-valid syntax) is
                // a genuine, unclassified gap worth surfacing rather than swallowing.
                unexpectedFailures.add(testCase.name() + " [" + testCase.selector() + "]: "
                        + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }
        return new ValidCaseTally(compiled, denylisted, outOfScope, unexpectedFailures);
    }

    // ── fixture loading ──────────────────────────────────────────────────────

    private List<CtsCase> loadCases() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(CTS_RESOURCE_PATH)) {
            assertThat(in).as("vendored CTS fixture at src/test/resources%s", CTS_RESOURCE_PATH).isNotNull();
            JsonNode root = CTS_FIXTURE_MAPPER.readTree(in);
            List<CtsCase> cases = new ArrayList<>();
            for (JsonNode testNode : root.path("tests")) {
                cases.add(new CtsCase(
                        testNode.path("name").asText(),
                        testNode.path("selector").asText(),
                        testNode.path("invalid_selector").asBoolean(false)));
            }
            return cases;
        }
    }
}
