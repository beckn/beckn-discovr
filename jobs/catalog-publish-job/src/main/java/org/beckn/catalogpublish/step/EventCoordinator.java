package org.beckn.catalogpublish.step;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.event.CatalogPersistedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class EventCoordinator {

    private final ApplicationEventPublisher springEvents;

    public EventCoordinator(ApplicationEventPublisher springEvents) {
        this.springEvents = springEvents;
    }

    /** Always publishes — each listener decides independently whether to act. */
    public void publishPersistedEvent(CatalogBatch batch) {
        springEvents.publishEvent(new CatalogPersistedEvent(this, batch));
    }
}
