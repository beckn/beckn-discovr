package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.ItemLocationCollection;

import java.util.List;

public interface ItemLocationCollectionStore {

    void saveLocations(List<ItemLocationCollection> locations);

    /** Deletes all location records for items belonging to the given catalog. Used by FULL replace mode. Returns count of deleted rows. */
    int deleteByCatalogId(String catalogId);

    /**
     * Deletes location records for the given items within a catalog. Used by MERGE mode to
     * re-derive each published item's locations cleanly (clears stale rows when a provider
     * reduces its {@code availableAt} geometries). Returns count of deleted rows.
     */
    int deleteByItemIdsAndCatalogId(List<String> itemIds, String catalogId);
}
