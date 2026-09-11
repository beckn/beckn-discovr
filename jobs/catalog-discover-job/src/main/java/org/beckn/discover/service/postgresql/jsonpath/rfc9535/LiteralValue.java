package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Infers the typed SQL cast + bound Java value for one RFC 9535 filter comparison literal.
 *
 * <p>Per the design doc's "Translation mapping table": the cast (numeric/timestamptz/text) is
 * chosen from the literal's own shape, and the literal itself is always bound as a {@code ?}
 * parameter — never concatenated into SQL text.</p>
 *
 * <p>The cast is chosen from the <i>literal's</i> shape, not from the compared field's actual
 * JSON type — so a raw {@code ::numeric}/{@code ::timestamptz}/{@code ::boolean} cast throws
 * whenever the field's real value doesn't parse as that type (e.g. {@code @.name < 5} against a
 * field that is genuinely a string). {@link #castExtraction} therefore routes non-text casts
 * through the exception-safe {@code try_to_numeric}/{@code try_to_timestamptz}/{@code
 * try_to_boolean} SQL functions (see {@code catalog-publish-job}'s Flyway migrations V6/V7),
 * which return {@code NULL} instead of raising on a type mismatch — making the comparison safely
 * evaluate to "no match", which is also the RFC 9535-correct behavior (section 2.3.5.2.2: a
 * comparison between different basic JSON value types is always false, never an error).</p>
 */
final class LiteralValue {

    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final String sqlCast;
    private final Object boundValue;

    private LiteralValue(String sqlCast, Object boundValue) {
        this.sqlCast = sqlCast;
        this.boundValue = boundValue;
    }

    String sqlCast() {
        return sqlCast;
    }

    Object boundValue() {
        return boundValue;
    }

    /**
     * Wraps {@code fieldExtraction} (a {@code #>>} text extraction) in the cast appropriate for
     * this literal's type — the exception-safe {@code try_to_*} SQL function for
     * numeric/timestamptz/boolean, or a plain {@code ::text} cast (which can never fail) for text.
     */
    String castExtraction(String fieldExtraction) {
        return switch (sqlCast) {
            case "numeric" -> "try_to_numeric(" + fieldExtraction + ")";
            case "timestamptz" -> "try_to_timestamptz(" + fieldExtraction + ")";
            case "boolean" -> "try_to_boolean(" + fieldExtraction + ")";
            default -> "(" + fieldExtraction + ")::" + sqlCast;
        };
    }

    static LiteralValue infer(String raw) {
        String trimmed = raw.trim();
        if (isQuoted(trimmed)) {
            String unquoted = trimmed.substring(1, trimmed.length() - 1);
            return tryParseTimestamp(unquoted)
                    .map(ts -> new LiteralValue("timestamptz", ts))
                    .orElseGet(() -> new LiteralValue("text", unquoted));
        }
        if (NUMERIC.matcher(trimmed).matches()) {
            return new LiteralValue("numeric", new java.math.BigDecimal(trimmed));
        }
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return new LiteralValue("boolean", Boolean.parseBoolean(trimmed));
        }
        // Fall back to text — an unquoted bareword that isn't a recognized literal shape.
        return new LiteralValue("text", trimmed);
    }

    private static boolean isQuoted(String s) {
        return s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"')
                && s.charAt(s.length() - 1) == s.charAt(0);
    }

    private static java.util.Optional<OffsetDateTime> tryParseTimestamp(String candidate) {
        try {
            return java.util.Optional.of(OffsetDateTime.parse(candidate));
        } catch (DateTimeParseException e) {
            return java.util.Optional.empty();
        }
    }
}
