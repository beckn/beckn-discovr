package org.beckn.discover.service.response;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared response utilities: context creation, validation, error handling,
 * and response building.
 *
 * <p>Catalog-level operations (offer filtering, schema filtering, dedup) live
 * in {@link CatalogProcessor} and are coordinated by
 * {@link CatalogPipeline}.</p>
 */
@Service
public class ResponseProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResponseProcessor.class);

    private final CatalogProcessor catalogProcessor;

    public ResponseProcessor(CatalogProcessor catalogProcessor) {
        this.catalogProcessor = catalogProcessor;
    }

    // ── Context creation ─────────────────────────────────────────────────────

    /** Copies the request context and sets {@code action = "on_discover"}. */
    public Context createResponseContext(Context requestContext) {
        Context ctx = new Context(requestContext);
        ctx.setAction("on_discover");
        return ctx;
    }

    /** Creates a minimal context for error cases where the request context is unavailable. */
    public Context createMinimalContext() {
        Context ctx = new Context();
        ctx.setMessageId("msg-" + System.currentTimeMillis());
        ctx.setBapId("unknown");
        ctx.setTransactionId("txn-" + System.currentTimeMillis());
        ctx.setTimestamp(OffsetDateTime.now());
        ctx.setAction("on_discover");
        ctx.setVersion("2.0.0");
        return ctx;
    }

    // ── Response building ─────────────────────────────────────────────────────

    /**
     * Builds a complete {@link DiscoverResponse} from the processed catalog
     * list.
     *
     * <p>Note: {@link CatalogPipeline} should have already run the
     * post-processing steps (dedup, cross-filter, schema filter) before
     * calling this method.  This method performs a final validation step and
     * falls back to an empty response if any catalog fails validation.</p>
     */
    public DiscoverResponse buildResponse(List<Catalog> catalogs, Context context) {
        Context ctx = context != null ? context : createMinimalContext();

        if (catalogs == null || catalogs.isEmpty()) {
            log.debug("response.build.empty transactionId={}",
                    ctx.getTransactionId());
            return buildEmptyResponse(ctx);
        }

        DiscoverResponse response = new DiscoverResponse();
        response.setContext(createResponseContext(ctx));
        DiscoverResponse.InReplyTo inReplyTo = ctx.getMessageId() != null
                ? new DiscoverResponse.InReplyTo(ctx.getMessageId(), null)
                : null;
        response.setMessage(new DiscoverResponse.ResponseMessage(catalogs, inReplyTo));

        if (!validateResponse(response)) {
            log.warn("response.build.validationFailed transactionId={}", ctx.getTransactionId());
            return buildEmptyResponse(ctx);
        }

        return response;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /** Returns {@code true} if the response has a context and at least one valid catalog. */
    public boolean validateResponse(DiscoverResponse response) {
        if (response == null || response.getContext() == null) return false;
        if (response.getCatalogs() == null || response.getCatalogs().isEmpty()) return false;
        return response.getCatalogs().stream().allMatch(catalogProcessor::validateCatalog);
    }

    // ── Empty / error responses ───────────────────────────────────────────────

    /** Returns an empty response with a properly-formed response context. */
    public DiscoverResponse buildEmptyResponse(Context context) {
        Context ctx = context != null ? context : createMinimalContext();
        DiscoverResponse response = new DiscoverResponse();
        response.setContext(createResponseContext(ctx));
        response.setMessage(new DiscoverResponse.ResponseMessage(new ArrayList<>()));
        return response;
    }

    /** Logs the error and returns an empty response. */
    public DiscoverResponse handleProcessingError(Exception error, Context context) {
        log.error("response.error transactionId={} error={}",
                context != null ? context.getTransactionId() : "unknown",
                error.getMessage(), error);
        return buildEmptyResponse(context);
    }
}
