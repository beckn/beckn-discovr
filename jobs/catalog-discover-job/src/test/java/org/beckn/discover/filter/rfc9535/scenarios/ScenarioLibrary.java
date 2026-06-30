package org.beckn.discover.filter.rfc9535.scenarios;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Exhaustive RFC 9535 scenario generator, organised by the RFC's feature matrix
 * and extended with Beckn offer queries. Every scenario carries an independently
 * computed expected result (from {@link CatalogDataset}), so the runner validates
 * actual PostgreSQL results — not merely that a query executed.
 *
 * <p>Coverage: child/descendant segments, wildcard, index (incl. negative),
 * slice, multi-selector; filter selectors with all comparison operators, logical
 * {@code && || !} and parentheses; existence tests; functions
 * {@code length/match/search}; literals (int/number/string/bool/null,
 * single+double quotes); namespaced keys; absolute+relative singular queries; and
 * offer queries at catalog and resource level.</p>
 */
final class ScenarioLibrary {

    private static final String[] OPS = {"==", "!=", "<", "<=", ">", ">="};
    private static final String[] CONNECTORS = {"CCS2", "CHAdeMO", "Type2", "Type1", "GBT", "Tesla"};
    private static final String[] CATEGORIES = {"EV", "Home", "Public", "Fleet"};
    private static final int[] POWERS = {5, 10, 20, 30, 50, 75, 100, 150, 200, 350};
    private static final String[] RATINGS = {"2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};

    private ScenarioLibrary() {
    }

    static List<Scenario> all(CatalogDataset ds) {
        List<Scenario> s = new ArrayList<>();
        numericResource(ds, s);
        namespacedPrice(ds, s);
        priceRanges(ds, s);
        stringEquality(ds, s);
        booleanAndNull(ds, s);
        logical(ds, s);
        existence(ds, s);
        functions(ds, s);
        lengthFn(ds, s);
        indicesAndSlices(ds, s);
        descendantAndWildcard(ds, s);
        catalogOffers(ds, s);
        resourceOffers(ds, s);
        valueSelections(ds, s);
        return s;
    }

    // ── Numeric comparisons on resource attributes (power, rating) ─────────────

    private static void numericResource(CatalogDataset ds, List<Scenario> s) {
        for (String op : OPS) {
            for (int t : POWERS) {
                String expr = "$.catalogs[*].resources[*][?@.resourceAttributes.power " + op + " " + t + "]";
                int th = t;
                s.add(Scenario.ids("numeric.power", expr,
                        ds.resourceIds(r -> cmpInt(r.path("resourceAttributes").path("power").asInt(), op, th))));
            }
            for (String rt : RATINGS) {
                String expr = "$.catalogs[*].resources[*][?@.rating.value " + op + " " + rt + "]";
                double th = Double.parseDouble(rt);
                s.add(Scenario.ids("numeric.rating", expr,
                        ds.resourceIds(r -> cmpDouble(r.path("rating").path("value").asDouble(), op, th))));
            }
        }
    }

    // ── Namespaced key numeric predicate (schema:price) ────────────────────────

    private static void namespacedPrice(CatalogDataset ds, List<Scenario> s) {
        for (String op : OPS) {
            for (int t = 10; t <= 40; t += 2) {
                String expr = "$.catalogs[*].resources[*][?@['schema:price'] " + op + " " + t + "]";
                int th = t;
                s.add(Scenario.ids("namespaced.price", expr,
                        ds.resourceIds(r -> cmpInt(r.path("schema:price").asInt(), op, th))));
            }
        }
    }

    // ── Two-sided "between range" predicates (the canonical example) ───────────

    private static void priceRanges(CatalogDataset ds, List<Scenario> s) {
        int[][] ranges = {{10, 20}, {15, 25}, {20, 30}, {25, 35}, {30, 40}, {12, 18}, {22, 33}, {10, 40}};
        for (int[] rng : ranges) {
            int lo = rng[0], hi = rng[1];
            String expr = "$.catalogs[*].resources[*][?@['schema:price'] >= " + lo
                    + " && @['schema:price'] <= " + hi + "]";
            s.add(Scenario.ids("range.price", expr, ds.resourceIds(r -> {
                int p = r.path("schema:price").asInt();
                return p >= lo && p <= hi;
            })));
        }
    }

    // ── String equality / inequality ───────────────────────────────────────────

    private static void stringEquality(CatalogDataset ds, List<Scenario> s) {
        for (String c : CONNECTORS) {
            for (String op : new String[]{"==", "!="}) {
                String expr = "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType " + op + " \"" + c + "\"]";
                s.add(Scenario.ids("string.connector", expr, ds.resourceIds(r -> {
                    boolean eq = r.path("resourceAttributes").path("connectorType").asText().equals(c);
                    return op.equals("==") == eq;
                })));
            }
        }
        for (String cat : CATEGORIES) {
            for (String op : new String[]{"==", "!="}) {
                // single-quoted literal — RFC permits both quote styles; must translate to PG double-quote
                String expr = "$.catalogs[*].resources[*][?@['beckn:category'] " + op + " '" + cat + "']";
                s.add(Scenario.ids("string.category", expr, ds.resourceIds(r -> {
                    boolean eq = r.path("beckn:category").asText().equals(cat);
                    return op.equals("==") == eq;
                })));
            }
        }
    }

    // ── Boolean and null literals ───────────────────────────────────────────---

    private static void booleanAndNull(CatalogDataset ds, List<Scenario> s) {
        s.add(Scenario.ids("bool.available.true", "$.catalogs[*].resources[*][?@.available == true]",
                ds.resourceIds(r -> r.path("available").asBoolean())));
        s.add(Scenario.ids("bool.available.false", "$.catalogs[*].resources[*][?@.available == false]",
                ds.resourceIds(r -> !r.path("available").asBoolean())));
        // null comparison: no resource has a null deletedAt field → empty result
        s.add(Scenario.ids("null.compare", "$.catalogs[*].resources[*][?@.deletedAt == null]",
                new TreeSet<>()));
    }

    // ── Logical &&, ||, !, parentheses ─────────────────────────────────────────

    private static void logical(CatalogDataset ds, List<Scenario> s) {
        for (String c : CONNECTORS) {
            for (int t : new int[]{10, 20, 30, 50, 75, 100, 125, 150, 200, 350}) {
                int th = t;
                String and = "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c
                        + "\" && @.resourceAttributes.power >= " + t + "]";
                s.add(Scenario.ids("logical.and", and, ds.resourceIds(r ->
                        r.path("resourceAttributes").path("connectorType").asText().equals(c)
                                && r.path("resourceAttributes").path("power").asInt() >= th)));

                String or = "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"" + c
                        + "\" || @.resourceAttributes.power < " + t + "]";
                s.add(Scenario.ids("logical.or", or, ds.resourceIds(r ->
                        r.path("resourceAttributes").path("connectorType").asText().equals(c)
                                || r.path("resourceAttributes").path("power").asInt() < th)));
            }
            String not = "$.catalogs[*].resources[*][?!(@.resourceAttributes.connectorType == \"" + c + "\")]";
            s.add(Scenario.ids("logical.not", not, ds.resourceIds(r ->
                    !r.path("resourceAttributes").path("connectorType").asText().equals(c))));
        }
        for (String rt : RATINGS) {
            double th = Double.parseDouble(rt);
            String expr = "$.catalogs[*].resources[*][?(@.rating.value >= " + rt
                    + " || @['schema:price'] < 15) && @.available == true]";
            s.add(Scenario.ids("logical.paren", expr, ds.resourceIds(r ->
                    (r.path("rating").path("value").asDouble() >= th || r.path("schema:price").asInt() < 15)
                            && r.path("available").asBoolean())));
        }
    }

    // ── Existence tests ─────────────────────────────────────────────────────---

    private static void existence(CatalogDataset ds, List<Scenario> s) {
        s.add(Scenario.ids("exists.rating", "$.catalogs[*].resources[*][?@.rating]",
                ds.resourceIds(r -> r.has("rating"))));
        s.add(Scenario.ids("exists.offers", "$.catalogs[*].resources[*][?@.offers]",
                ds.resourceIds(r -> r.has("offers"))));
        s.add(Scenario.ids("exists.none", "$.catalogs[*].resources[*][?@.nonexistentField]",
                new TreeSet<>()));
        s.add(Scenario.ids("exists.and", "$.catalogs[*].resources[*][?@.rating && @.offers]",
                ds.resourceIds(r -> r.has("rating") && r.has("offers"))));
        s.add(Scenario.ids("exists.not", "$.catalogs[*].resources[*][?!@.nonexistentField]",
                ds.resourceIds(r -> !r.has("nonexistentField"))));
        s.add(Scenario.ids("exists.nested", "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType]",
                ds.resourceIds(r -> r.path("resourceAttributes").has("connectorType"))));
    }

    // ── Functions: match (anchored), search (substring) ────────────────────────

    private static void functions(CatalogDataset ds, List<Scenario> s) {
        for (String c : CONNECTORS) {
            // match() is a full match → exact connector
            s.add(Scenario.ids("fn.match.exact", "$.catalogs[*].resources[*][?match(@.resourceAttributes.connectorType, \"" + c + "\")]",
                    ds.resourceIds(r -> r.path("resourceAttributes").path("connectorType").asText().equals(c))));
        }
        s.add(Scenario.ids("fn.match.regex", "$.catalogs[*].resources[*][?match(@.resourceAttributes.connectorType, \"Type.*\")]",
                ds.resourceIds(r -> r.path("resourceAttributes").path("connectorType").asText().matches("Type.*"))));
        s.add(Scenario.ids("fn.match.regex2", "$.catalogs[*].resources[*][?match(@.resourceAttributes.connectorType, \"CCS.\")]",
                ds.resourceIds(r -> r.path("resourceAttributes").path("connectorType").asText().matches("CCS."))));
        s.add(Scenario.ids("fn.search.substr", "$.catalogs[*].resources[*][?search(@.descriptor.name, \"Charger\")]",
                ds.resourceIds(r -> r.path("descriptor").path("name").asText().contains("Charger"))));
        s.add(Scenario.ids("fn.search.substr2", "$.catalogs[*].resources[*][?search(@.descriptor.name, \"Station\")]",
                ds.resourceIds(r -> r.path("descriptor").path("name").asText().contains("Station"))));
    }

    private static void lengthFn(CatalogDataset ds, List<Scenario> s) {
        for (String op : OPS) {
            for (int t = 0; t <= 3; t++) {
                int th = t;
                String expr = "$.catalogs[*].resources[*][?length(@.tags) " + op + " " + t + "]";
                s.add(Scenario.ids("fn.length.tags", expr,
                        ds.resourceIds(r -> cmpInt(r.path("tags").size(), op, th))));
            }
        }
    }

    // ── Index, negative index, slice, multi-index ──────────────────────────────

    private static void indicesAndSlices(CatalogDataset ds, List<Scenario> s) {
        // resources[0] → first resource of each catalog
        s.add(Scenario.ids("index.first", "$.catalogs[*].resources[0]",
                pickSlice(ds, 0, 1)));
        // resources[-1] → last resource of each catalog
        s.add(Scenario.ids("index.last", "$.catalogs[*].resources[-1]",
                pickLast(ds)));
        // resources[0:2] → first two (RFC end exclusive)
        s.add(Scenario.ids("slice.0to2", "$.catalogs[*].resources[0:2]",
                pickSlice(ds, 0, 2)));
        // resources[1:] → from index 1
        s.add(Scenario.ids("slice.1toEnd", "$.catalogs[*].resources[1:]",
                pickSlice(ds, 1, Integer.MAX_VALUE)));
        // resources[:1] → first only
        s.add(Scenario.ids("slice.toEnd1", "$.catalogs[*].resources[:1]",
                pickSlice(ds, 0, 1)));
        // multi-index resources[0,1]
        s.add(Scenario.ids("multi.index", "$.catalogs[*].resources[0,1]",
                pickSlice(ds, 0, 2)));
    }

    // ── Descendant & wildcard whole-set selections ──────────────────────────---

    private static void descendantAndWildcard(CatalogDataset ds, List<Scenario> s) {
        // all resources
        s.add(Scenario.ids("wildcard.allResources", "$.catalogs[*].resources[*]",
                ds.resourceIds(r -> true)));
        // all offers anywhere (catalog-level + resource-level)
        TreeSet<String> allOffers = new TreeSet<>(ds.catalogOfferIds(o -> true));
        allOffers.addAll(ds.resourceOfferIds(o -> true));
        s.add(Scenario.ids("descendant.allOffers", "$..offers[*]", allOffers));
    }

    // ── Offer queries: catalog level ───────────────────────────────────────---

    private static void catalogOffers(CatalogDataset ds, List<Scenario> s) {
        s.add(Scenario.ids("offer.catalog.all", "$.catalogs[*].offers[*]",
                ds.catalogOfferIds(o -> true)));
        s.add(Scenario.ids("offer.catalog.hasResourceIds", "$.catalogs[*].offers[*][?@.resourceIds]",
                ds.catalogOfferIds(o -> o.has("resourceIds"))));
        for (String op : OPS) {
            for (int t : new int[]{100, 125, 150, 175, 200, 225, 250, 275}) {
                int th = t;
                String expr = "$.catalogs[*].offers[*][?@.price " + op + " " + t + "]";
                s.add(Scenario.ids("offer.catalog.price", expr,
                        ds.catalogOfferIds(o -> cmpInt(o.path("price").asInt(), op, th))));
            }
        }
        // validity date string compare
        s.add(Scenario.ids("offer.catalog.validity", "$.catalogs[*].offers[*][?@.validity.startDate == \"2026-01-01\"]",
                ds.catalogOfferIds(o -> o.path("validity").path("startDate").asText().equals("2026-01-01"))));
    }

    // ── Offer queries: resource level ─────────────────────────────────────────

    private static void resourceOffers(CatalogDataset ds, List<Scenario> s) {
        s.add(Scenario.ids("offer.resource.all", "$.catalogs[*].resources[*].offers[*]",
                ds.resourceOfferIds(o -> true)));
        for (String op : OPS) {
            for (int t : new int[]{50, 65, 80, 95, 110, 125, 140, 155, 170, 185}) {
                int th = t;
                String expr = "$.catalogs[*].resources[*].offers[*][?@.price " + op + " " + t + "]";
                s.add(Scenario.ids("offer.resource.price", expr,
                        ds.resourceOfferIds(o -> cmpInt(o.path("price").asInt(), op, th))));
            }
        }
    }

    // ── Value-level selection (scalars, not ids) ───────────────────────────────

    private static void valueSelections(CatalogDataset ds, List<Scenario> s) {
        s.add(Scenario.values("value.allPrices", "$.catalogs[*].resources[*]['schema:price']",
                prices(ds, r -> true)));
        s.add(Scenario.values("value.evPrices",
                "$.catalogs[*].resources[*][?@['beckn:category'] == \"EV\"]['schema:price']",
                prices(ds, r -> r.path("beckn:category").asText().equals("EV"))));
        s.add(Scenario.values("value.ccs2Prices",
                "$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == \"CCS2\"]['schema:price']",
                prices(ds, r -> r.path("resourceAttributes").path("connectorType").asText().equals("CCS2"))));
    }

    // ── Oracle helpers ──────────────────────────────────────────────────────---

    private static List<String> prices(CatalogDataset ds, Predicate<JsonNode> p) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : ds.resources()) {
            if (p.test(r)) out.add(r.path("schema:price").asText());
        }
        out.sort(Comparator.comparingInt(Integer::parseInt));
        return out;
    }

    private static TreeSet<String> pickLast(CatalogDataset ds) {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode cat : ds.catalogs()) {
            JsonNode res = cat.get("resources");
            if (res.size() > 0) out.add(res.get(res.size() - 1).get("id").asText());
        }
        return out;
    }

    private static TreeSet<String> pickSlice(CatalogDataset ds, int start, int endExclusive) {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode cat : ds.catalogs()) {
            JsonNode res = cat.get("resources");
            int end = Math.min(endExclusive, res.size());
            for (int i = start; i < end; i++) out.add(res.get(i).get("id").asText());
        }
        return out;
    }

    private static boolean cmpInt(int a, String op, int b) {
        return switch (op) {
            case "==" -> a == b;
            case "!=" -> a != b;
            case "<" -> a < b;
            case "<=" -> a <= b;
            case ">" -> a > b;
            case ">=" -> a >= b;
            default -> false;
        };
    }

    private static boolean cmpDouble(double a, String op, double b) {
        return switch (op) {
            case "==" -> a == b;
            case "!=" -> a != b;
            case "<" -> a < b;
            case "<=" -> a <= b;
            case ">" -> a > b;
            case ">=" -> a >= b;
            default -> false;
        };
    }
}
