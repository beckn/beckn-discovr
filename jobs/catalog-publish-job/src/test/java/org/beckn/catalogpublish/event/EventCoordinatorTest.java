package org.beckn.catalogpublish.event;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.beckn.catalogpublish.step.EventCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventCoordinatorTest {

    @Test
    void schedulePostCommitPublish_alwaysPublishesCatalogPersistedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        EventCoordinator coordinator = new EventCoordinator(publisher);
        CatalogContext ctx = new CatalogContext("b1", "http://b1", new String[0], null);
        CatalogBatch batch = new CatalogBatch("c1", ctx, null, CatalogOperation.PUBLISH,
                List.of(), List.of(), Map.of(), false);

        coordinator.schedulePostCommitPublish(batch);

        verify(publisher).publishEvent(any(CatalogPersistedEvent.class));
    }
}
