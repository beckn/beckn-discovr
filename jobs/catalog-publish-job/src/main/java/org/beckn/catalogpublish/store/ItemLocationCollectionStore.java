package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.ItemLocationCollection;

import java.util.List;

public interface ItemLocationCollectionStore {

    void saveLocations(List<ItemLocationCollection> locations);

    /** Deletes all location records for items belonging to the given catalog and BPP. Used by FULL replace mode. Returns count of deleted rows. */
    int deleteByCatalogIdAndBppId(String catalogId, String bppId);
}
