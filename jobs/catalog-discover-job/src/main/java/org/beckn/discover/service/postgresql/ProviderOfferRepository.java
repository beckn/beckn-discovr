package org.beckn.discover.service.postgresql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only repository for provider-level offers.
 * Provider offers are stored in {@code provider_offer} by the catalog-publish-job
 * and resolved at search time via {@link ProviderOfferEnricher}.
 */
@Repository
public class ProviderOfferRepository {

    private static final Logger log = LoggerFactory.getLogger(ProviderOfferRepository.class);

    private static final String FIND_BY_PROVIDER_IDS = """
            SELECT offer_id, provider_id, payload
            FROM provider_offer
            WHERE provider_id IN (:providerIds)
            """;

    private final JdbcClient jdbcClient;

    public ProviderOfferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns all provider-level offers for the given provider IDs.
     *
     * @param providerIds non-empty set of provider IDs
     * @return list of rows with offer_id, provider_id, payload columns
     */
    public List<Map<String, Object>> findByProviderIds(Set<String> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) return List.of();
        return jdbcClient.sql(FIND_BY_PROVIDER_IDS)
                .param("providerIds", List.copyOf(providerIds))
                .query()
                .listOfRows();
    }
}
