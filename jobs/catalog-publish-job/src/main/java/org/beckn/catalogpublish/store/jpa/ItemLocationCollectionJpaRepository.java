package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.beckn.catalogpublish.model.ItemLocationId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemLocationCollectionJpaRepository
        extends JpaRepository<ItemLocationCollection, ItemLocationId> {}
