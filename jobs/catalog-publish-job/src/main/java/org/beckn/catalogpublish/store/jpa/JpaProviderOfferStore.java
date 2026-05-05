package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ProviderOffer;
import org.beckn.catalogpublish.store.ProviderOfferStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Primary
public class JpaProviderOfferStore implements ProviderOfferStore {

    private final ProviderOfferJpaRepository repo;

    public JpaProviderOfferStore(ProviderOfferJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ProviderOffer> saveAll(List<ProviderOffer> offers) {
        for (var offer : offers) {
            repo.upsert(offer.getOfferId(), offer.getCatalogId(), offer.getProviderId(), offer.getPayload());
        }
        return offers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteByCatalogId(String catalogId) {
        return repo.deleteByCatalogId(catalogId);
    }
}
