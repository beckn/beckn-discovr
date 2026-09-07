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
