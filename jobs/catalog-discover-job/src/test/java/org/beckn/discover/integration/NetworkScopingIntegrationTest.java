package org.beckn.discover.integration;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for #309: discover must scope results to the request's
 * {@code context.networkId}. Two catalogs are published — one to network A, one to
 * network B — both satisfying the same JSONPath predicate. A query carrying network A
 * must return only the network-A catalog, and a query carrying network B only the
 * network-B one.
 *
 * <p>Asserts at the PostgreSQL query-engine level ({@link PostgreSQLQueryEngine}), which
 * exercises the complete network-scoping path: {@link QueryRequest#from} pulling
 * {@code networkId} off the context, {@code PostgreSQLService} threading it into the
 * builder, the {@code ? = ANY(i.network_id)} predicate, the real Postgres execution, and
 * assembly. (The downstream response pipeline's offer narrowing is unrelated to #309.)</p>
 */
class NetworkScopingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PostgreSQLQueryEngine pgQueryEngine;

    private static final String NET_A = "net.example/alpha";
    private static final String NET_B = "net.example/bravo";
    private static final String FILTER = "$.catalogs[*].resources[*] ? (@.resourceAttributes.netTest == true)";

    private void seedItem(String itemId, String catalogId, String network) {
        String payload = """
                {"catalogs":[{
                  "id":"%s",
                  "descriptor":{"name":"Catalog %s"},
                  "provider":{"id":"prov-%s","descriptor":{"name":"Provider %s"}},
                  "resources":[{"id":"%s","descriptor":{"name":"Resource %s"},
                    "resourceAttributes":{"netTest":true}}]
                }]}""".formatted(catalogId, catalogId, catalogId, catalogId, itemId, itemId);
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO item (id, catalog_id, context_url, type, network_id, offer_ids, payload, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ARRAY[]::TEXT[], ?, NOW()) "
                            + "ON CONFLICT (id, catalog_id) DO NOTHING");
            ps.setString(1, itemId);
            ps.setString(2, catalogId);
            ps.setString(3, "https://schema.beckn.io/GroceryResource/v2.1/context.jsonld");
            ps.setString(4, "groc:GroceryResource");
            ps.setArray(5, connection.createArrayOf("text", new String[]{network}));
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(payload);
            ps.setObject(6, jsonb);
            return ps;
        });
    }

    private QueryRequest filterQueryForNetwork(String network) {
        DiscoverRequest req = new DiscoverRequest();
        var ctx = buildContext("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        ctx.setNetworkId(network);
        req.setContext(ctx);
        req.setFilters(FILTER);
        return QueryRequest.from(req);
    }

    @Test
    @DisplayName("J query carrying networkId=A returns only the network-A catalog (B excluded)")
    void jsonPathQuery_scopedToRequestingNetwork() throws Exception {
        seedItem("res-A", "cat-A", NET_A);
        seedItem("res-B", "cat-B", NET_B);

        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(filterQueryForNetwork(NET_A));

        assertThat(catalogs).extracting(Catalog::getId).containsExactly("cat-A");
        assertThat(catalogs).extracting(Catalog::getId).doesNotContain("cat-B");
    }

    @Test
    @DisplayName("the same query carrying networkId=B returns only the network-B catalog (symmetry)")
    void jsonPathQuery_otherNetwork_returnsOnlyItsOwn() throws Exception {
        seedItem("res-A", "cat-A", NET_A);
        seedItem("res-B", "cat-B", NET_B);

        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(filterQueryForNetwork(NET_B));

        assertThat(catalogs).extracting(Catalog::getId).containsExactly("cat-B");
    }

    @Test
    @DisplayName("a network with no catalogs returns nothing (no cross-network leak)")
    void jsonPathQuery_unknownNetwork_returnsEmpty() throws Exception {
        seedItem("res-A", "cat-A", NET_A);
        seedItem("res-B", "cat-B", NET_B);

        List<Catalog> catalogs = pgQueryEngine.executeFilterQuery(filterQueryForNetwork("net.example/no-such"));

        assertThat(catalogs).isEmpty();
    }
}
