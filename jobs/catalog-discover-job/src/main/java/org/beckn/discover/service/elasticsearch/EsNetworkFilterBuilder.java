package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.beckn.discover.service.engine.QueryRequest;

import java.util.Optional;

/**
 * Stateless utility that builds the network-scoping Elasticsearch filter (#309).
 *
 * <p>Discover results must be scoped to the requesting network ({@code context.networkId}).
 * The {@code network_id} field is a {@code keyword} that holds one or more network ids per
 * document; a {@code term} query matches when the array contains the requested id — exactly
 * the membership semantics needed. This mirrors the PostgreSQL
 * {@code ? = ANY(i.network_id)} predicate so both engines scope identically.</p>
 *
 * <p>Returns {@link Optional#empty()} when the request carries no network id, so callers
 * stay network-agnostic in that (non-production) case. The value is bound by the ES client —
 * never string-concatenated.</p>
 */
public final class EsNetworkFilterBuilder {

    static final String FIELD_NETWORK = "network_id";

    private EsNetworkFilterBuilder() {
        // utility class — no instances
    }

    /** Builds the {@code term(network_id = ...)} filter, or empty when no network id is present. */
    public static Optional<Query> build(QueryRequest request) {
        if (request == null || !request.hasNetworkFilter()) {
            return Optional.empty();
        }
        String networkId = request.networkId();
        return Optional.of(Query.of(q -> q.term(t -> t
                .field(FIELD_NETWORK)
                .value(networkId))));
    }
}
