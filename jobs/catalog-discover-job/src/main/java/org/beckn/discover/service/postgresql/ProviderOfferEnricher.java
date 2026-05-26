package org.beckn.discover.service.postgresql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.TimeUnit;

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

    /**
     * H2: Per-provider-ID offer cache — TTL 60 s.
     *
     * <p>Provider-level offers change only on catalog publish events (infrequent).
     * Caching eliminates the SELECT ... FROM provider_offer round-trip on every
     * non-empty discovery response. Each entry is keyed by provider_id; a sentinel
     * empty list is cached on cache miss so that providers with no offers also skip
     * the DB call for 60 s.</p>
     */
    private static final Cache<String, List<Map<String, Object>>> OFFER_CACHE =
            Caffeine.newBuilder()
                    .expireAfterWrite(60, TimeUnit.SECONDS)
                    .maximumSize(1024)
                    .build();

    private final ProviderOfferRepository repository;
    private final ObjectMapper objectMapper;

    public ProviderOfferEnricher(ProviderOfferRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Clears the provider offer cache. Exposed for testing only. */
    static void clearCacheForTesting() {
        OFFER_CACHE.invalidateAll();
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

        // H2: resolve per-provider-ID from cache; only query DB for IDs not yet cached.
        Set<String> uncachedIds = new LinkedHashSet<>();
        for (String pid : providerIds) {
            if (OFFER_CACHE.getIfPresent(pid) == null) {
                uncachedIds.add(pid);
            }
        }

        if (!uncachedIds.isEmpty()) {
            List<Map<String, Object>> rows = repository.findByProviderIds(uncachedIds);
            // Group rows by provider_id and populate cache (one entry per provider)
            Map<String, List<Map<String, Object>>> byPid = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String pid = Objects.toString(row.get("provider_id"), null);
                if (pid != null) byPid.computeIfAbsent(pid, k -> new ArrayList<>()).add(row);
            }
            // Cache fetched providers (may be empty list = no offers)
            for (String pid : uncachedIds) {
                OFFER_CACHE.put(pid, byPid.getOrDefault(pid, List.of()));
            }
        }

        // Now build offer map from cache for all provider IDs
        Map<String, List<Object>> offersByProviderId = new HashMap<>();
        for (String pid : providerIds) {
            List<Map<String, Object>> cachedRows = OFFER_CACHE.getIfPresent(pid);
            if (cachedRows != null && !cachedRows.isEmpty()) {
                List<Object> offers = new ArrayList<>();
                for (Map<String, Object> row : cachedRows) {
                    Object payloadRaw = row.get("payload");
                    if (payloadRaw == null) continue;
                    try {
                        Object offer = objectMapper.readValue(payloadRaw.toString(), Object.class);
                        offers.add(offer);
                    } catch (Exception e) {
                        log.warn("event={} providerId={} error={}",
                                LogEvent.PROVIDER_OFFER_ENRICHED, ErrorSanitizer.sanitize(pid),
                                ErrorSanitizer.sanitize(e));
                    }
                }
                if (!offers.isEmpty()) offersByProviderId.put(pid, offers);
            }
        }

        if (offersByProviderId.isEmpty()) return;

        int totalAppended = 0;
        for (Catalog catalog : catalogs) {
            String providerId = catalog.getProvider() != null ? catalog.getProvider().getId() : null;
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
            if (catalog.getProvider() != null && catalog.getProvider().getId() != null
                    && !catalog.getProvider().getId().isBlank()) {
                ids.add(catalog.getProvider().getId());
            }
        }
        return ids;
    }

}
