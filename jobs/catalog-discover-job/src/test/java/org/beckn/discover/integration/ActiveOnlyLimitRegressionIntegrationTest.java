package org.beckn.discover.integration;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: the {@code activeOnly} filter must run <b>in-query, before {@code LIMIT}</b>.
 *
 * <p>With {@code result-limit=2}, three <i>inactive</i> catalogs are seeded with the newest
 * {@code updated_at} (so they sort first under {@code ORDER BY updated_at DESC}) ahead of two
 * <i>active</i> ones. A post-fetch/post-pipeline filter would take the top-2 rows (both inactive)
 * and then drop them → zero results. Because the predicate lives in the SQL {@code WHERE}, the
 * inactive rows are excluded before {@code LIMIT}, so the two active catalogs survive.</p>
 */
@TestPropertySource(properties = "discovery.postgresql.result-limit=2")
class ActiveOnlyLimitRegressionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PostgreSQLQueryEngine pgQueryEngine;

    private static final String TAG = "limitreg";

    private void seed(String itemId, String catalogId, boolean active, String updatedAt) {
        String isActive = active ? "" : "\"isActive\":false,";
        String payload = ("""
                {"catalogs":[{
                  "id":"%s",
                  "descriptor":{"name":"Catalog %s"},
                  %s
                  "provider":{"id":"prov","descriptor":{"name":"Provider"}},
                  "resources":[{"id":"%s","descriptor":{"name":"Resource"},
                    "resourceAttributes":{"tag":"%s"}}]
                }]}""").formatted(catalogId, catalogId, isActive, itemId, TAG);
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO item (id, catalog_id, context_url, type, network_id, offer_ids, payload, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ARRAY[]::TEXT[], ?, ?::timestamp) "
                            + "ON CONFLICT (id, catalog_id) DO UPDATE SET payload = EXCLUDED.payload, updated_at = EXCLUDED.updated_at");
            ps.setString(1, itemId);
            ps.setString(2, catalogId);
            ps.setString(3, "https://schema.beckn.io/GroceryResource/v2.1/context.jsonld");
            ps.setString(4, "groc:GroceryResource");
            ps.setArray(5, connection.createArrayOf("text", new String[]{DEFAULT_TEST_NETWORK}));
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(payload);
            ps.setObject(6, jsonb);
            ps.setString(7, updatedAt);
            return ps;
        });
    }

    private QueryRequest query(boolean activeOnly) {
        DiscoverRequest req = new DiscoverRequest();
        var ctx = buildContext("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        ctx.setNetworkId(DEFAULT_TEST_NETWORK);
        req.setContext(ctx);
        req.setFilters("$.catalogs[*].resources[*] ? (@.resourceAttributes.tag == \"" + TAG + "\")");
        // Map the legacy single flag onto the value-match API: activeOnly ⇒ active=TRUE + validity=TRUE.
        return QueryRequest.from(req, activeOnly ? Boolean.TRUE : null, activeOnly ? Boolean.TRUE : null);
    }

    @Test
    @DisplayName("with limit=2, activeOnly=true returns the 2 active catalogs despite 3 newer inactive ones ahead of them")
    void activeFilter_runsBeforeLimit() throws Exception {
        // Three inactive, newest timestamps → they sort first and would fill a post-filter LIMIT 2.
        seed("i1", "cat-inactive-1", false, "2026-06-01 12:00:00");
        seed("i2", "cat-inactive-2", false, "2026-06-01 11:00:00");
        seed("i3", "cat-inactive-3", false, "2026-06-01 10:00:00");
        // Two active, older timestamps → only reachable within LIMIT 2 if inactive are excluded first.
        seed("a1", "cat-active-1", true, "2026-05-01 12:00:00");
        seed("a2", "cat-active-2", true, "2026-05-01 11:00:00");

        // Baseline: unfiltered, the cap grabs the 2 newest — both inactive.
        List<Catalog> unfiltered = pgQueryEngine.executeFilterQuery(query(false));
        assertThat(unfiltered).extracting(Catalog::getId)
                .containsExactly("cat-inactive-1", "cat-inactive-2");

        // activeOnly: inactive excluded in WHERE (before LIMIT) → the 2 active survive the cap.
        List<Catalog> active = pgQueryEngine.executeFilterQuery(query(true));
        assertThat(active).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-active-1", "cat-active-2");
        assertThat(active).extracting(Catalog::getId)
                .doesNotContain("cat-inactive-1", "cat-inactive-2", "cat-inactive-3");
    }
}
