package org.beckn.discover.service.postgresql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.Provider;
import org.beckn.discover.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderOfferEnricherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProviderOfferRepository repository;
    private ProviderOfferEnricher enricher;

    @BeforeEach
    void setUp() {
        // H2: clear the static Caffeine cache so tests are independent of each other
        ProviderOfferEnricher.clearCacheForTesting();
        repository = mock(ProviderOfferRepository.class);
        enricher = new ProviderOfferEnricher(repository, objectMapper);
    }

    @Test
    void enrich_appendsProviderOffersToMatchingCatalogs() {
        var catalog = buildCatalog("cat-1", "provider-abc");
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        when(repository.findByProviderIds(Set.of("provider-abc")))
                .thenReturn(List.of(
                        Map.of("offer_id", "o1", "provider_id", "provider-abc",
                                "payload", "{\"id\":\"o1\",\"descriptor\":{\"name\":\"10% Off\"}}")
                ));

        enricher.enrich(catalogs);

        assertThat(catalogs.get(0).getOffers()).hasSize(1);
        @SuppressWarnings("unchecked")
        var offer = (Map<String, Object>) catalogs.get(0).getOffers().get(0);
        assertThat(offer.get("id")).isEqualTo("o1");
    }

    @Test
    void enrich_multipleProviders_enrichesEachCorrectly() {
        var catA = buildCatalog("cat-a", "prov-a");
        var catB = buildCatalog("cat-b", "prov-b");
        List<Catalog> catalogs = new ArrayList<>(List.of(catA, catB));

        when(repository.findByProviderIds(Set.of("prov-a", "prov-b")))
                .thenReturn(List.of(
                        Map.of("offer_id", "oa", "provider_id", "prov-a",
                                "payload", "{\"id\":\"oa\"}"),
                        Map.of("offer_id", "ob1", "provider_id", "prov-b",
                                "payload", "{\"id\":\"ob1\"}"),
                        Map.of("offer_id", "ob2", "provider_id", "prov-b",
                                "payload", "{\"id\":\"ob2\"}")
                ));

        enricher.enrich(catalogs);

        assertThat(catA.getOffers()).hasSize(1);
        assertThat(catB.getOffers()).hasSize(2);
    }

    @Test
    void enrich_noProviderIds_skipsRepository() {
        var catalog = new Catalog();
        catalog.setId("cat-1");
        catalog.setResources(new ArrayList<>());
        // No providerId set
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        enricher.enrich(catalogs);

        verify(repository, never()).findByProviderIds(org.mockito.ArgumentMatchers.anySet());
        assertThat(catalog.getOffers()).isNull();
    }

    @Test
    void enrich_emptyList_noop() {
        enricher.enrich(List.of());
        verify(repository, never()).findByProviderIds(org.mockito.ArgumentMatchers.anySet());
    }

    @Test
    void enrich_nullList_noop() {
        enricher.enrich(null);
        verify(repository, never()).findByProviderIds(org.mockito.ArgumentMatchers.anySet());
    }

    @Test
    void enrich_noMatchingOffers_doesNotModifyCatalog() {
        var catalog = buildCatalog("cat-1", "provider-abc");
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        when(repository.findByProviderIds(Set.of("provider-abc")))
                .thenReturn(List.of());

        enricher.enrich(catalogs);

        assertThat(catalog.getOffers()).isEmpty();
    }

    @Test
    void enrich_preservesExistingOffers() {
        var catalog = buildCatalog("cat-1", "provider-abc");
        catalog.setOffers(new ArrayList<>(List.of(Map.of("id", "existing-offer"))));
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        when(repository.findByProviderIds(Set.of("provider-abc")))
                .thenReturn(List.of(
                        Map.of("offer_id", "new-offer", "provider_id", "provider-abc",
                                "payload", "{\"id\":\"new-offer\"}")
                ));

        enricher.enrich(catalogs);

        assertThat(catalog.getOffers()).hasSize(2);
    }

    @Test
    void enrich_malformedPayload_skipsOffer() {
        var catalog = buildCatalog("cat-1", "provider-abc");
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        when(repository.findByProviderIds(Set.of("provider-abc")))
                .thenReturn(List.of(
                        Map.of("offer_id", "bad", "provider_id", "provider-abc",
                                "payload", "not-json{{{")
                ));

        enricher.enrich(catalogs);

        assertThat(catalog.getOffers()).isEmpty();
    }

    /**
     * H2: Calling enrich() twice for the same provider IDs must result in only one
     * DB round-trip — the second call should be served entirely from the Caffeine cache.
     */
    @Test
    void enrich_calledTwiceForSameProvider_dbQueriedOnlyOnce() {
        var catalog1 = buildCatalog("cat-1", "provider-abc");
        var catalog2 = buildCatalog("cat-2", "provider-abc");

        when(repository.findByProviderIds(Set.of("provider-abc")))
                .thenReturn(List.of(
                        Map.of("offer_id", "o1", "provider_id", "provider-abc",
                                "payload", "{\"id\":\"o1\",\"descriptor\":{\"name\":\"10% Off\"}}")
                ));

        // First call — hits the DB and populates the cache
        enricher.enrich(new ArrayList<>(List.of(catalog1)));
        // Second call — same provider ID should be served from cache
        enricher.enrich(new ArrayList<>(List.of(catalog2)));

        // Repository must have been called only once despite two enrich() invocations
        verify(repository, times(1)).findByProviderIds(Set.of("provider-abc"));

        // Both catalogs should have the offer appended
        assertThat(catalog1.getOffers()).hasSize(1);
        assertThat(catalog2.getOffers()).hasSize(1);
        @SuppressWarnings("unchecked")
        var offer1 = (Map<String, Object>) catalog1.getOffers().get(0);
        assertThat(offer1.get("id")).isEqualTo("o1");
    }

    private Catalog buildCatalog(String id, String providerId) {
        var catalog = new Catalog();
        catalog.setId(id);
        catalog.setProvider(new Provider(providerId, null));
        catalog.setResources(new ArrayList<>());
        catalog.setOffers(new ArrayList<>());
        return catalog;
    }
}
