package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.Item;

import java.util.List;

public interface ItemStore {

    List<Item> saveAll(List<Item> items);

    /**
     * Batch lookup by id. The item PK is {@code (id)} as of V13 — no BPP filter needed.
     */
    List<Item> findAllByIdIn(List<String> itemIds);

    /**
     * Finds all items whose {@code offer_ids} array contains any of the supplied offer ids,
     * regardless of which BPP owns them. Used by Phase 2 offer propagation.
     */
    List<Item> findAllByAnyOfferId(List<String> offerIds);
}
