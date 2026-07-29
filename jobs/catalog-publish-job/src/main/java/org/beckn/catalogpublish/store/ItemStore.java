package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.Item;

import java.util.List;
import java.util.Optional;

public interface ItemStore {

    List<Item> saveAll(List<Item> items);

    List<Item> findAllByIdInAndCatalogId(List<String> itemIds, String catalogId);

    List<Item> findAllByCatalogIdAndAnyOfferId(String catalogId, List<String> offerIds);

    /** Finds items by their IDs across all catalogs. Used by Phase 3 cross-catalog offer resolution. */
    List<Item> findAllByIdIn(List<String> itemIds);

    /** Finds all items of a catalog. Used by Phase 3.5 catalog-metadata propagation. */
    List<Item> findAllByCatalogId(String catalogId);

    /** One arbitrary item of a catalog, as a sample of its stored catalog metadata. Empty if none. */
    Optional<Item> findFirstByCatalogId(String catalogId);

    /** Deletes all items belonging to the given catalog. Used by FULL replace mode. Returns count of deleted rows. */
    int deleteByCatalogId(String catalogId);
}
