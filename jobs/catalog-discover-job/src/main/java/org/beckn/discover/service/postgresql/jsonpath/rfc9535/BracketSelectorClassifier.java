package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import java.util.regex.Pattern;

/**
 * Classifies the raw text of one {@code snack4-jsonpath} {@code Segment} into the RFC 9535
 * selector kind it represents.
 *
 * <p>{@code snack4-jsonpath} exposes only {@code Segment.getOriginalText()} (raw source text) —
 * it does not expose a structured selector object (see
 * docs/design/DESIGN-rfc9535-jsonpath-grammar.md "Genuine open choice: RFC 9535 parser library").
 * This class is the compiler's own small, first-character-dispatch classifier over that raw
 * text, mirroring the equally trivial dispatch already visible in the library's own
 * {@code SelectSegment} constructor.</p>
 */
final class BracketSelectorClassifier {

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    enum Kind { NAME, WILDCARD, INDEX, LAST, SLICE, SLICE_WITH_STEP, FILTER }

    record Classification(Kind kind, String content) {
    }

    /**
     * Classifies one segment's {@code getOriginalText()}.
     *
     * @param originalText either a dot-name form (e.g. {@code ".store"}) or a bracket form
     *                      (e.g. {@code "['store']"}, {@code "[0]"}, {@code "[?(@.a<1)]"})
     */
    Classification classify(String originalText) {
        if (originalText == null || originalText.isEmpty()) {
            throw new InvalidRfc9535SyntaxException("Empty segment text", null);
        }
        if (originalText.charAt(0) == '.') {
            String name = originalText.substring(1);
            return name.equals("*")
                    ? new Classification(Kind.WILDCARD, "*")
                    : new Classification(Kind.NAME, name);
        }
        if (originalText.charAt(0) == '[') {
            String inner = stripBrackets(originalText);
            return classifyBracketContent(inner);
        }
        throw new InvalidRfc9535SyntaxException("Unrecognized segment text: " + originalText, null);
    }

    private static String stripBrackets(String text) {
        int close = text.lastIndexOf(']');
        return close > 1 ? text.substring(1, close) : text.substring(1);
    }

    private Classification classifyBracketContent(String inner) {
        String trimmed = inner.trim();
        if (trimmed.equals("*")) {
            return new Classification(Kind.WILDCARD, "*");
        }
        if (trimmed.startsWith("?")) {
            return new Classification(Kind.FILTER, stripOuterParens(trimmed.substring(1).trim()));
        }
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"')) {
            return new Classification(Kind.NAME, trimmed.substring(1, trimmed.length() - 1));
        }
        if (trimmed.equalsIgnoreCase("last")) {
            return new Classification(Kind.LAST, trimmed);
        }
        if (INTEGER.matcher(trimmed).matches()) {
            return new Classification(Kind.INDEX, trimmed);
        }
        if (trimmed.contains(":")) {
            long colons = trimmed.chars().filter(c -> c == ':').count();
            return colons >= 2
                    ? new Classification(Kind.SLICE_WITH_STEP, trimmed)
                    : new Classification(Kind.SLICE, trimmed);
        }
        // Bare unquoted name inside brackets (uncommon but RFC 9535-legal for some emitters).
        return new Classification(Kind.NAME, trimmed);
    }

    private static String stripOuterParens(String text) {
        if (text.length() >= 2 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
