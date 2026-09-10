package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.integration.BaseIntegrationTest;
import org.beckn.discover.service.postgresql.QueryBuilderHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result-correctness coverage for {@link Rfc9535SqlPredicateCompiler}'s generalized recursion
 * contract — array-producing selectors (wildcard/filter/slice/index/{@code last}) followed by
 * further segments open a correlated {@code EXISTS} level and compilation continues recursively,
 * at arbitrary depth (see docs/design/DESIGN-rfc9535-jsonpath-grammar.md "Recursion contract").
 *
 * <p>Reproduces the design doc's own spike Case 2 shape
 * ({@code resources[*].offers[?(@.validity.endDate >= ...)]}) plus a 3-level-deep case, verified
 * against real query results from a real Postgres instance — not just SQL-string shape.</p>
 */
class Rfc9535SqlPredicateCompilerNestedIntegrationTest extends BaseIntegrationTest {

    private static final String ITEM_ID = "nested-rfc9535-item";
    private static final String CATALOG_ID = "nested-rfc9535-catalog";

    private final Rfc9535SqlPredicateCompiler compiler = new Rfc9535SqlPredicateCompiler();

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedNestedFixture() {
        // catalogs[0].resources[*] each carry their own offers[] — the real Discovr payload
        // shape this test exists to reproduce (see task rationale: catalogs[0].resources[*],
        // each resource having its own offers array).
        String payload = """
                {
                  "catalogs": [
                    {
                      "id": "%s",
                      "resources": [
                        {
                          "id": "res-1",
                          "offers": [
                            {"id": "offer-future", "price": 50,
                             "validity": {"endDate": "2030-01-01T00:00:00Z"}},
                            {"id": "offer-past", "price": 150,
                             "validity": {"endDate": "2020-01-01T00:00:00Z"}}
                          ]
                        },
                        {
                          "id": "res-2",
                          "offers": [
                            {"id": "offer-future-2", "price": 30,
                             "validity": {"endDate": "2031-01-01T00:00:00Z"}}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(CATALOG_ID);

        jdbcTemplate.update("DELETE FROM item WHERE id = ? AND catalog_id = ?", ITEM_ID, CATALOG_ID);
        jdbcTemplate.update("""
                        INSERT INTO item (id, catalog_id, context_url, type, network_id, offer_ids, payload,
                                           created_by, updated_by, updated_at)
                        VALUES (?, ?, 'https://schema.org/Ev', 'EvCharger', ARRAY[?]::text[],
                                ARRAY[]::TEXT[], CAST(? AS jsonb), 'test', 'test', now())
                        """,
                ITEM_ID, CATALOG_ID, DEFAULT_TEST_NETWORK, payload);
    }

    @Test
    @DisplayName("design-doc spike Case 2 shape: resources[*].offers[?(@.validity.endDate >= X)] "
            + "matches across every resource's offers, not just one fixed index")
    void twoLevelWildcardThenFilter_matchesAcrossAllResources() {
        String expression = "$.catalogs[0].resources[*].offers[?(@.validity.endDate >= \"2025-01-01T00:00:00Z\")]";
        CompiledPredicate predicate = compile(expression);

        assertThat(predicate.whereFragment()).contains("EXISTS (SELECT 1 FROM jsonb_array_elements(");
        assertThat(predicate.offersProjectionFragment()).isPresent();

        assertThat(rowMatches(predicate)).as("item has at least one offer past 2025").isTrue();

        List<String> matchedOfferIds = matchedOfferIds(predicate);
        assertThat(matchedOfferIds)
                .as("both future-dated offers, from different resources, must be included; "
                        + "the past-dated offer must be excluded")
                .containsExactlyInAnyOrder("offer-future", "offer-future-2");
    }

    @Test
    @DisplayName("3-level-deep chain: catalogs[*].resources[*].offers[?(@.price < X)] generalizes "
            + "beyond a single nested level")
    void threeLevelChain_generalizesRecursion() {
        String expression = "$.catalogs[*].resources[*].offers[?(@.price < 100)]";
        CompiledPredicate predicate = compile(expression);

        assertThat(rowMatches(predicate)).isTrue();
        assertThat(matchedOfferIds(predicate))
                .containsExactlyInAnyOrder("offer-future", "offer-future-2");
    }

    @Test
    @DisplayName("nested chain with no matching offers correctly yields no match (loud false, not silent-wrong)")
    void nestedChain_noMatch_whenNoOfferSatisfies() {
        String expression = "$.catalogs[0].resources[*].offers[?(@.price > 10000)]";
        CompiledPredicate predicate = compile(expression);

        assertThat(rowMatches(predicate)).isFalse();
    }

    @Test
    @DisplayName("hostile member name inside a nested filter is bound, not concatenated, and still matches correctly")
    void nestedChain_hostileMemberName_isBoundAndMatchesCorrectly() {
        // Re-seed with a hostile attribute name nested under offers, reachable only via the
        // resources[*] mid-path EXISTS level exercised by this test class.
        String hostileName = "a'{},b";
        String payload = """
                {"catalogs":[{"id":"%s","resources":[{"id":"res-1","offers":[
                  {"id":"offer-hostile","%s": 42}
                ]}]}]}
                """.formatted(CATALOG_ID, hostileName);
        jdbcTemplate.update("UPDATE item SET payload = CAST(? AS jsonb) WHERE id = ? AND catalog_id = ?",
                payload, ITEM_ID, CATALOG_ID);

        String expression = "$.resources[*].offers[?(@[\"" + hostileName + "\"] == 42)]";
        CompiledPredicate predicate = compile(expression);

        assertThat(predicate.whereFragment()).doesNotContain(hostileName);
        assertThat(rowMatches(predicate)).isTrue();
    }

    @Test
    @DisplayName("equality filter '==' executes against real Postgres without a SQL syntax error "
            + "and matches only the offer with the queried id")
    void equalityFilter_executesAgainstPostgresAndMatchesExactOffer() {
        String expression = "$.catalogs[0].resources[*].offers[?(@.id == \"offer-future\")]";
        CompiledPredicate predicate = compile(expression);

        assertThat(rowMatches(predicate)).as("Postgres accepts the compiled '=' comparison").isTrue();
        assertThat(matchedOfferIds(predicate)).containsExactly("offer-future");
    }

    private CompiledPredicate compile(String expression) {
        return compiler.toSqlPredicate(JsonPath.parse(expression).getSegments());
    }

    private boolean rowMatches(CompiledPredicate predicate) {
        QueryBuilderHelper.QuerySpec spec = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(predicate.whereFragment(), predicate.whereParameters().toArray())
                .condition("i.id = ? AND i.catalog_id = ?", ITEM_ID, CATALOG_ID)
                .build(10);
        List<Map<String, Object>> rows = jdbcClient.sql(spec.sql()).params(spec.parameters()).query().listOfRows();
        return !rows.isEmpty();
    }

    private List<String> matchedOfferIds(CompiledPredicate predicate) {
        QueryBuilderHelper.QueryTemplate template = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                .condition(predicate.whereFragment(), predicate.whereParameters().toArray())
                .condition("i.id = ? AND i.catalog_id = ?", ITEM_ID, CATALOG_ID);
        predicate.offersProjectionFragment().ifPresent(fragment -> template.projectionColumn(
                QueryBuilderHelper.MATCHING_OFFERS_ALIAS, fragment, predicate.projectionParameters().toArray()));
        QueryBuilderHelper.QuerySpec spec = template.build(10);

        List<Map<String, Object>> rows = jdbcClient.sql(spec.sql()).params(spec.parameters()).query().listOfRows();
        assertThat(rows).hasSize(1);
        Object matchingOffers = rows.get(0).get(QueryBuilderHelper.MATCHING_OFFERS_ALIAS);
        assertThat(matchingOffers).isNotNull();

        JsonNode offersArray = toJsonNode(matchingOffers);
        return offersArray.findValuesAsText("id");
    }

    private JsonNode toJsonNode(Object pgJsonb) {
        try {
            return objectMapper.readTree(pgJsonb.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse jsonb result", e);
        }
    }
}
