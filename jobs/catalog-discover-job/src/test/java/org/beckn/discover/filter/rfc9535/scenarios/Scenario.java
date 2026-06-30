package org.beckn.discover.filter.rfc9535.scenarios;

import java.util.List;
import java.util.Set;

/**
 * One RFC 9535 test scenario.
 *
 * <ul>
 *   <li>{@code expectedIds != null} → the translated path must select exactly
 *       these node ids (resources/offers) — full result validation.</li>
 *   <li>{@code expectedValues != null} → the translated path must select exactly
 *       these scalar values (e.g. prices) — value-level result validation.</li>
 *   <li>both null → execution-only (must translate + run without error).</li>
 * </ul>
 */
record Scenario(String category, String expr, Set<String> expectedIds, List<String> expectedValues) {

    static Scenario ids(String category, String expr, Set<String> expectedIds) {
        return new Scenario(category, expr, expectedIds, null);
    }

    static Scenario values(String category, String expr, List<String> expectedValues) {
        return new Scenario(category, expr, null, expectedValues);
    }

    static Scenario execOnly(String category, String expr) {
        return new Scenario(category, expr, null, null);
    }

    boolean validatesResult() {
        return expectedIds != null || expectedValues != null;
    }
}
