package org.beckn.discover.service.postgresql.jsonpath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts JSONPath filters to PostgreSQL-compatible format (quote colons, single to double quotes).
 */
@Component
public class JsonPathConverter {

    private static final Logger log = LoggerFactory.getLogger(JsonPathConverter.class);

    private static final Pattern COLON_FIELD_PATTERN = Pattern
            .compile("(?<![\"'])\\b([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z0-9_:]+)\\b");

    public String processFilter(String userFilter) {
        if (userFilter == null || userFilter.trim().isEmpty()) {
            return "";
        }
        String trimmed = userFilter.trim();
        log.debug("Processing JSONPath: {}", trimmed);
        String result = quoteColonFields(trimmed);
        log.debug("Final PostgreSQL JSONPath: {}", result);
        return result;
    }

    private String quoteColonFields(String condition) {
        if (condition == null || condition.isEmpty()) {
            return condition;
        }
        String withDoubleQuotes = condition.replaceAll("'((?:[^'\\\\]|\\\\.)*)'", "\"$1\"");
        Matcher matcher = COLON_FIELD_PATTERN.matcher(withDoubleQuotes);
        StringBuilder result = new StringBuilder(withDoubleQuotes.length() + 16);
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement("\"" + fieldName + "\""));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
