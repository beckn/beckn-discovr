package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.ProviderOffer;

import java.util.List;

public interface ProviderOfferStore {

    List<ProviderOffer> saveAll(List<ProviderOffer> offers);

    int deleteByCatalogId(String catalogId);
}
