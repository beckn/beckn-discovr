package org.beckn.catalogpublish.store;

import org.beckn.catalogpublish.model.ItemLocationCollection;

import java.util.List;

public interface ItemLocationCollectionStore {

    void saveLocations(List<ItemLocationCollection> locations);
}
