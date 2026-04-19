package org.beckn.discover.service.postgresql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Post-pipeline enrichment: appends provider-level offers to search results.
 *
 * <p>Provider-level offers are offers published without {@code resourceIds} — they apply
 * to ALL resources from that provider. They are stored in the {@code provider_offer} table
 * (one row per offer, not stamped into each item) and resolved at search time.</p>
 *
 * <p>This enricher runs AFTER the {@link org.beckn.discover.service.response.CatalogPipeline}
 * so that {@code filterOffersByResourceIds} never sees provider offers (they have no resourceIds
 * and would be incorrectly filtered out).</p>
 */
@Component
public class ProviderOfferEnricher {

    private static final Logger log = LoggerFactory.getLogger(ProviderOfferEnricher.class);

    private final ProviderOfferRepository repository;
    private final ObjectMapper objectMapper;

    public ProviderOfferEnricher(ProviderOfferRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches the given catalogs with provider-level offers.
     *
     * <ol>
     *   <li>Collect unique provider IDs from all catalogs</li>
     *   <li>Query provider_offer table for matching offers</li>
     *   <li>Group offers by provider_id</li>
     *   <li>Append matching offers to each catalog's offers list</li>
     * </ol>
     *
     * @param catalogs mutable list of catalogs from any search engine, post-pipeline
     */
    public void enrich(List<Catalog> catalogs) {
        if (catalogs == null || catalogs.isEmpty()) return;

        Set<String> providerIds = collectProviderIds(catalogs);
        if (providerIds.isEmpty()) return;

        List<Map<String, Object>> rows = repository.findByProviderIds(providerIds);
        if (rows.isEmpty()) return;

        Map<String, List<Object>> offersByProviderId = groupByProviderId(rows);
        if (offersByProviderId.isEmpty()) return;

        int totalAppended = 0;
        for (Catalog catalog : catalogs) {
            String providerId = catalog.getProviderId();
            if (providerId == null || providerId.isBlank()) continue;

            List<Object> providerOffers = offersByProviderId.get(providerId);
            if (providerOffers == null || providerOffers.isEmpty()) continue;

            // Build a mutable copy — catalog.getOffers() may be an immutable
            // list produced by CatalogPipeline (Stream.toList()).
            List<Object> merged = catalog.getOffers() != null
                    ? new ArrayList<>(catalog.getOffers())
                    : new ArrayList<>();
            merged.addAll(providerOffers);
            catalog.setOffers(merged);
            totalAppended += providerOffers.size();
        }

        if (totalAppended > 0) {
            log.info("event={} providerCount={} offersAppended={}",
                    LogEvent.PROVIDER_OFFER_ENRICHED, providerIds.size(), totalAppended);
        }
    }

    private Set<String> collectProviderIds(List<Catalog> catalogs) {
        Set<String> ids = new LinkedHashSet<>();
        for (Catalog catalog : catalogs) {
            if (catalog.getProviderId() != null && !catalog.getProviderId().isBlank()) {
                ids.add(catalog.getProviderId());
            }
        }
        return ids;
    }

    private Map<String, List<Object>> groupByProviderId(List<Map<String, Object>> rows) {
        Map<String, List<Object>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String providerId = Objects.toString(row.get("provider_id"), null);
            if (providerId == null) continue;

            Object payloadRaw = row.get("payload");
            if (payloadRaw == null) continue;

            try {
                Object offer = objectMapper.readValue(payloadRaw.toString(), Object.class);
                grouped.computeIfAbsent(providerId, k -> new ArrayList<>()).add(offer);
            } catch (Exception e) {
                log.warn("event={} providerId={} error={}",
                        LogEvent.PROVIDER_OFFER_ENRICHED, ErrorSanitizer.sanitize(providerId),
                        ErrorSanitizer.sanitize(e));
            }
        }
        return grouped;
    }
}
