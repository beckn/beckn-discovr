package org.beckn.catalogpublish.controller;

import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.beckn.catalogpublish.util.CorrelationContext;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async processing bridge for the HTTP push path.
 * Decouples the HTTP response (202 Accepted) from catalog processing so the BPP
 * is not blocked while the pipeline runs.
 */
@Service
public class CatalogPushService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPushService.class);

    private final CatalogPublishOrchestrator orchestrator;
    private final CorrelationContext correlationContext;

    public CatalogPushService(CatalogPublishOrchestrator orchestrator,
            CorrelationContext correlationContext) {
        this.orchestrator = orchestrator;
        this.correlationContext = correlationContext;
    }

    /**
     * Processes the raw catalog push payload asynchronously.
     * The HTTP response has already been sent (202) before this runs.
     */
    @Async("catalogProcessingExecutor")
    public void processAsync(String rawBody) {
        try {
            correlationContext.populateFallback();
            orchestrator.processPublish(rawBody);
        } catch (Exception e) {
            log.error("event={} error={}", LogEvent.CONSUMER_ERROR, ErrorSanitizer.sanitize(e));
        } finally {
            correlationContext.clear();
        }
    }
}
