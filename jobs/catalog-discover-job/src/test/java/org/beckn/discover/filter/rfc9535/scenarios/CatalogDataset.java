package org.beckn.discover.filter.rfc9535.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Deterministic, realistic Beckn catalog dataset used as the <b>oracle</b> for
 * RFC 9535 translation tests. The same JSON is loaded into PostgreSQL and parsed
 * here in Java, so the expected result of any scenario is computed independently
 * of the translator — the test compares "what PostgreSQL returned for the
 * translated path" against "what plain Java says the answer should be".
 *
 * <p>Shape mirrors {@code on_discover}: {@code catalogs[] → resources[] → offers[]}
 * plus catalog-level {@code offers[]}. Every resource carries the full attribute
 * set so comparisons never hit missing-node ambiguity.</p>
 */
final class CatalogDataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;     // { "catalogs": [ ... ] }
    private final String json;

    private CatalogDataset(String json) throws Exception {
        this.json = json;
        this.root = MAPPER.readTree(json);
    }

    String json() {
        return json;
    }

    // ── Build ────────────────────────────────────────────────────────────────

    static CatalogDataset build() {
        String[] connectors = {"CCS2", "CHAdeMO", "Type2", "Type1", "GBT", "Tesla"};
        int[] powers = {5, 10, 20, 30, 50, 75, 100, 150, 200, 350};
        String[] ratings = {"2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
        String[] categories = {"EV", "Home", "Public", "Fleet"};
        String[] names = {"Fast Charger", "Slow Charger", "Home Plug", "Highway DC", "Mall Station"};

        int NUM_CATALOGS = 60;
        StringBuilder cats = new StringBuilder();
        for (int i = 0; i < NUM_CATALOGS; i++) {
            int resCount = 2 + (i % 2); // 2 or 3 resources
            StringBuilder resources = new StringBuilder();
            StringBuilder firstResId = new StringBuilder();
            for (int r = 0; r < resCount; r++) {
                int k = i * 3 + r;
                String resId = "res_" + i + "_" + r;
                if (r == 0) firstResId.append(resId);
                String conn = connectors[k % connectors.length];
                int power = powers[k % powers.length];
                String rating = ratings[k % ratings.length];
                String cat = categories[k % categories.length];
                String nm = names[k % names.length];
                int price = 10 + (k % 30);           // 10..39 (integer → exact value compare)
                boolean available = (k % 2 == 0);
                int tagCount = (k % 4);              // 0..3 → exercises length()
                int offerPrice = 50 + (k % 10) * 15; // 50..185

                StringBuilder tags = new StringBuilder("[");
                for (int t = 0; t < tagCount; t++) tags.append(t > 0 ? "," : "").append("\"t").append(t).append("\"");
                tags.append("]");

                if (r > 0) resources.append(",");
                resources.append("{")
                        .append("\"id\":\"").append(resId).append("\",")
                        .append("\"descriptor\":{\"name\":\"").append(nm).append("\"},")
                        .append("\"resourceAttributes\":{\"connectorType\":\"").append(conn)
                        .append("\",\"power\":").append(power).append("},")
                        .append("\"rating\":{\"value\":").append(rating).append("},")
                        .append("\"available\":").append(available).append(",")
                        .append("\"schema:price\":").append(price).append(",")
                        .append("\"beckn:category\":\"").append(cat).append("\",")
                        .append("\"networkId\":\"n").append(i % 5).append("\",")
                        .append("\"tags\":").append(tags).append(",")
                        .append("\"offers\":[{\"id\":\"roff_").append(i).append("_").append(r)
                        .append("\",\"price\":").append(offerPrice)
                        .append(",\"resourceIds\":[\"").append(resId).append("\"]}]")
                        .append("}");
            }
            int catOfferPrice = 100 + (i % 8) * 25; // 100..275
            String start = String.format("2026-%02d-01", 1 + (i % 12));
            String end = String.format("2026-%02d-28", 1 + (i % 12));
            String catalog = "{"
                    + "\"id\":\"cat" + i + "\","
                    + "\"descriptor\":{\"name\":\"Catalog " + i + "\"},"
                    + "\"resources\":[" + resources + "],"
                    + "\"offers\":[{\"id\":\"coff_" + i + "\",\"price\":" + catOfferPrice
                    + ",\"validity\":{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"},"
                    + "\"resourceIds\":[\"" + firstResId + "\"]}]"
                    + "}";
            if (i > 0) cats.append(",");
            cats.append(catalog);
        }
        try {
            return new CatalogDataset("{\"catalogs\":[" + cats + "]}");
        } catch (Exception e) {
            throw new IllegalStateException("failed to build dataset", e);
        }
    }

    // ── Oracle: node streams ───────────────────────────────────────────────---

    List<JsonNode> catalogs() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode cat : root.get("catalogs")) out.add(cat);
        return out;
    }

    List<JsonNode> resources() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode cat : root.get("catalogs")) {
            for (JsonNode res : cat.get("resources")) out.add(res);
        }
        return out;
    }

    List<JsonNode> catalogOffers() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode cat : root.get("catalogs")) {
            for (JsonNode off : cat.get("offers")) out.add(off);
        }
        return out;
    }

    List<JsonNode> resourceOffers() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode res : resources()) {
            for (JsonNode off : res.get("offers")) out.add(off);
        }
        return out;
    }

    // ── Oracle: id-set collectors ──────────────────────────────────────────---

    Set<String> resourceIds(Predicate<JsonNode> p) {
        return ids(resources(), p);
    }

    Set<String> catalogOfferIds(Predicate<JsonNode> p) {
        return ids(catalogOffers(), p);
    }

    Set<String> resourceOfferIds(Predicate<JsonNode> p) {
        return ids(resourceOffers(), p);
    }

    private static Set<String> ids(List<JsonNode> nodes, Predicate<JsonNode> p) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : nodes) {
            if (p.test(n)) out.add(n.get("id").asText());
        }
        return out;
    }
}
