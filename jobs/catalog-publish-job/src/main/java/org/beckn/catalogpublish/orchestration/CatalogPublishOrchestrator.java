package org.beckn.catalogpublish.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.dto.PublishOutcome;
import org.beckn.catalogpublish.step.EventCoordinator;
import org.beckn.catalogpublish.step.ParseStep;
import org.beckn.catalogpublish.step.PersistenceStep;
import org.beckn.catalogpublish.step.ResultStep;
import org.beckn.catalogpublish.step.ValidateStep;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.CorrelationContext;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.beckn.catalogpublish.util.MdcSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.beckn.catalogpublish.dto.CatalogOperation.PUBLISH;

@Service
public class CatalogPublishOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublishOrchestrator.class);

    /** True on pool threads (set by TaskDecorator); unset when CallerRunsPolicy runs inline so we don't clear consumer MDC. */
    public static final ThreadLocal<Boolean> CATALOG_PROC_THREAD = new ThreadLocal<>();

    private final TransactionTemplate txTemplate;
    private final ParseStep parseStep;
    private final ValidateStep validateStep;
    private final PersistenceStep persistenceStep;
    private final EventCoordinator eventCoordinator;
    private final ResultStep resultStep;
    private final Executor catalogProcessingExecutor;
    private final CorrelationContext correlationContext;
    private final int parallelCatalogThreshold;

    public CatalogPublishOrchestrator(TransactionTemplate txTemplate,
            ParseStep parseStep,
            ValidateStep validateStep,
            PersistenceStep persistenceStep,
            EventCoordinator eventCoordinator,
            ResultStep resultStep,
            @Qualifier("catalogProcessingExecutor") Executor catalogProcessingExecutor,
            CorrelationContext correlationContext,
            AppProperties props) {
        this.txTemplate = txTemplate;
        this.parseStep = parseStep;
        this.validateStep = validateStep;
        this.persistenceStep = persistenceStep;
        this.eventCoordinator = eventCoordinator;
        this.resultStep = resultStep;
        this.catalogProcessingExecutor = catalogProcessingExecutor;
        this.correlationContext = correlationContext;
        this.parallelCatalogThreshold = props.catalog().parallelCatalogThreshold();
    }

    public PublishOutcome processPublish(String rawMessage) {
        ParsedCatalogMessage parsed = parseStep.parse(rawMessage);
        String messageId = parsed.context().contextNode().path(BecknFields.MESSAGE_ID).asText(null);
        correlationContext.populate(parsed.context(), messageId);
        validateStep.validate(parsed);
        // Pass the message node so PersistenceStep can read message-level publishDirectives array.
        JsonNode messageNode = parsed.rootNode().path(BecknFields.MESSAGE);
        List<ProcessingResult> results = processInParallel(parsed.catalogs(), parsed.context(),
                (node, ctx) -> executeInTransaction(node, ctx,
                        (n, c) -> persistenceStep.persistItemsAndLocations(n, c, PUBLISH, messageNode),
                        "catalog.publish"));
        return new PublishOutcome(parsed.context(), results);
    }

    private List<ProcessingResult> processInParallel(
            List<JsonNode> catalogs,
            CatalogContext ctx,
            BiFunction<JsonNode, CatalogContext, ProcessingResult> handler) {

        if (catalogs.isEmpty())
            return List.of();

        if (catalogs.size() < parallelCatalogThreshold) {
            return catalogs.stream()
                    .map(catalogNode -> handler.apply(catalogNode, ctx))
                    .toList();
        }

        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        List<CompletableFuture<ProcessingResult>> futures = catalogs.stream()
                .map(catalogNode -> {
                    String catalogId = parseStep.extractCatalogIdSafe(catalogNode);
                    return CompletableFuture.supplyAsync(
                            () -> MdcSupport.runWithSnapshot(mdcSnapshot,
                                    Boolean.TRUE.equals(CATALOG_PROC_THREAD.get()),
                                    () -> handler.apply(catalogNode, ctx)),
                            catalogProcessingExecutor)
                            .orTimeout(4, TimeUnit.MINUTES)
                            .exceptionally(e -> {
                                Throwable cause = e instanceof CompletionException ce && ce.getCause() != null
                                        ? ce.getCause()
                                        : e;
                                log.error("event={} catalogId={} error={}",
                                        LogEvent.PERSIST_FAILED, catalogId, ErrorSanitizer.sanitize(cause));
                                return ProcessingResult.internalError(catalogId, cause);
                            });
                })
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private ProcessingResult executeInTransaction(JsonNode catalogNode, CatalogContext ctx,
            BiFunction<JsonNode, CatalogContext, CatalogBatch> persistFn,
            String opLabel) {
        String catalogId = parseStep.extractCatalogIdSafe(catalogNode);
        try {
            ProcessingResult result = txTemplate.execute(status -> {
                CatalogBatch batch = persistFn.apply(catalogNode, ctx);
                eventCoordinator.schedulePostCommitPublish(batch);
                return resultStep.buildResult(batch);
            });
            if (result == null) {
                log.error("event={} opLabel={} catalogId={}", LogEvent.PERSIST_FAILED, opLabel, catalogId);
                return ProcessingResult.internalError(catalogId,
                        new IllegalStateException("TransactionTemplate returned null"));
            }
            return result;
        } catch (Exception e) {
            log.error("event={} opLabel={} catalogId={} error={}", LogEvent.PERSIST_FAILED, opLabel, catalogId, ErrorSanitizer.sanitize(e));
            return ProcessingResult.internalError(catalogId, e);
        }
    }
}
