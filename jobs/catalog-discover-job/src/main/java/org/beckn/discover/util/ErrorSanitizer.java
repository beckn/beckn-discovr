package org.beckn.discover.util;

import java.util.regex.Pattern;

/**
 * Strips stack traces, credentials, internal paths from exception messages
 * and sanitizes user-supplied strings before logging.
 */
public final class ErrorSanitizer {

    private ErrorSanitizer() {
    }

    private static final Pattern PASS_PATTERN   = Pattern.compile("(?i)password=\\S+");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)secret=\\S+");
    private static final Pattern JDBC_PATTERN   = Pattern.compile("jdbc:[^\\s]+");
    private static final Pattern TOKEN_PATTERN  = Pattern.compile("(?i)(bearer|token)=\\S+");
    private static final Pattern CTRL_PATTERN   = Pattern.compile("[\\r\\n\\t\\p{Cntrl}]");

    /** Sanitizes a Throwable message for safe logging. */
    public static String sanitize(Throwable t) {
        if (t == null)
            return "unknown error";
        String msg = t.getMessage();
        if (msg == null || msg.isBlank())
            return t.getClass().getSimpleName();
        return truncate(scrub(msg), 200);
    }

    /**
     * Sanitizes a user-supplied string for safe logging.
     * Strips credentials, JDBC URLs, tokens, control characters and truncates to 200 chars.
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank())
            return "";
        return truncate(CTRL_PATTERN.matcher(scrub(input)).replaceAll(" "), 200);
    }

    private static String scrub(String msg) {
        msg = PASS_PATTERN.matcher(msg).replaceAll("password=***");
        msg = SECRET_PATTERN.matcher(msg).replaceAll("secret=***");
        msg = TOKEN_PATTERN.matcher(msg).replaceAll("$1=***");
        msg = JDBC_PATTERN.matcher(msg).replaceAll("jdbc:***");
        return msg;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
