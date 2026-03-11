package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.Item;

import java.util.List;

public interface ItemStore {

    List<Item> saveAll(List<Item> items);

    List<Item> findAllByIdInAndBppId(List<String> itemIds, String bppId);

    List<Item> findAllByBppIdAndAnyOfferId(String bppId, List<String> offerIds);
}
