package org.beckn.discover.util;

import org.beckn.discover.model.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Utility helpers specific to discovery service custom logic.
 */
public final class DiscoveryServiceUtil {

    private DiscoveryServiceUtil() {
    }

    /**
     * Checks if a string is not blank (not null, not empty, and not only whitespace).
     *
     * @param str String to check
     * @return true if the string is not blank, false otherwise
     */
    public static boolean isNotBlank(String str) {
        return str != null && !str.trim().isEmpty();
    }

    /**
     * Checks if a string is blank (null, empty, or only whitespace).
     *
     * @param str String to check
     * @return true if the string is blank, false otherwise
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    private static boolean isNullOrEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    /**
     * Extracts type fragments from the schema context URLs, if present.
     *
     * @param context discovery request context
     * @return list of unique fragments
     */
    public static List<String> extractSchemaTypes(Context context) {
        return extractSchemaContextParts(context).types();
    }

    /**
     * Extracts schema context URLs without fragment identifiers.
     *
     * @param context discovery request context
     * @return list of unique base context URLs
     */
    public static List<String> extractSchemaContextUrls(Context context) {
        return extractSchemaContextParts(context).urls();
    }

    /** Single pass over context schema URLs to extract both types and base URLs. */
    public static SchemaContextParts extractSchemaContextParts(Context context) {
        if (context == null) {
            return new SchemaContextParts(Collections.emptyList(), Collections.emptyList());
        }
        return extractSchemaContextParts(context.getSchemaContext());
    }

    /** Single pass over a raw URL list to extract both type fragments and base URLs. */
    public static SchemaContextParts extractSchemaContextParts(List<String> rawUrls) {
        if (isNullOrEmpty(rawUrls)) {
            return new SchemaContextParts(Collections.emptyList(), Collections.emptyList());
        }
        HashSet<String> distinctTypes = new HashSet<>();
        HashSet<String> distinctUrls = new HashSet<>();
        for (String rawUrl : rawUrls) {
            String base = extractBaseUrl(rawUrl);
            String type = extractFragment(rawUrl);
            if (base != null && !base.isBlank()) {
                distinctUrls.add(base.trim());
            }
            if (type != null && !type.isBlank()) {
                distinctTypes.add(type.trim());
            }
        }
        return new SchemaContextParts(new ArrayList<>(distinctTypes), new ArrayList<>(distinctUrls));
    }

    public record SchemaContextParts(List<String> types, List<String> urls) {}

    // --- URL parsing utilities ---

    /**
     * Extracts the base URL (everything before the '#' fragment).
     * E.g. "https://example.com/schema#Item" → "https://example.com/schema"
     */
    public static String extractBaseUrl(String url) {
        int hashIndex = url.indexOf('#');
        if (hashIndex < 0) return url;
        String before = url.substring(0, hashIndex).trim();
        return before.isEmpty() ? url : before;
    }

    /**
     * Extracts the fragment part of a URL (everything after '#'), or null if absent.
     * E.g. "https://example.com/schema#Item" → "Item"
     */
    public static String extractFragment(String url) {
        int h = url.indexOf('#');
        if (h < 0 || h == url.length() - 1) return null;
        String f = url.substring(h + 1).trim();
        return f.isEmpty() ? null : f;
    }

}
