package org.beckn.catalogpublish.event;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Spring event published inside the transaction; delivered AFTER_COMMIT to listeners.
 * Carries the persisted {@link CatalogBatch} (including pre-parsed payload nodes) and
 * a snapshot of the MDC so the post-commit thread can restore request-scoped logging context.
 */
public class CatalogPersistedEvent extends ApplicationEvent {

    private final CatalogBatch batch;
    private final Map<String, String> mdcContext;

    public CatalogPersistedEvent(Object source, CatalogBatch batch) {
        super(source);
        this.batch = batch;
        this.mdcContext = org.slf4j.MDC.getCopyOfContextMap();
    }

    public CatalogBatch getBatch() { return batch; }
    public Map<String, String> getMdcContext() { return mdcContext; }
}
