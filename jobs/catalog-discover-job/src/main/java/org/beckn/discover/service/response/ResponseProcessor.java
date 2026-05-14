package org.beckn.discover.service.response;

import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /** Our service's BPP identity stamped onto outbound on_discover. Blank = no stamping. */
    @Value("${discovery.bpp-id:}")
    private String bppId;

    /** Our service's public BPP URI stamped onto outbound on_discover. Blank = no stamping. */
    @Value("${discovery.bpp-uri:}")
    private String bppUri;

    public ResponseProcessor(CatalogProcessor catalogProcessor) {
        this.catalogProcessor = catalogProcessor;
    }

    // ── Context creation ─────────────────────────────────────────────────────

    /**
     * Copies the request context and sets {@code action = "on_discover"}.
     * If {@code discovery.bpp-id} / {@code discovery.bpp-uri} are configured, also stamps
     * our service's BPP identity onto the response context. When unset (or blank), the
     * bpp fields remain whatever the request had (typically null) — preserves
     * backward-compatible behavior.
     */
    public Context createResponseContext(Context requestContext) {
        Context ctx = new Context(requestContext);
        ctx.setAction("on_discover");
        if (bppId != null && !bppId.isBlank()) {
            ctx.setBppId(bppId);
        }
        if (bppUri != null && !bppUri.isBlank()) {
            ctx.setBppUri(bppUri);
        }
        return ctx;
    }

    /** Creates a minimal context for error cases where the request context is unavailable. */
    public Context createMinimalContext() {
        Context ctx = new Context();
        ctx.setMessageId("msg-" + System.currentTimeMillis());
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
            log.debug(LogEvent.RESPONSE_BUILD_EMPTY);
            return buildEmptyResponse(ctx);
        }

        DiscoverResponse response = new DiscoverResponse();
        response.setContext(createResponseContext(ctx));
        DiscoverResponse.RequestDigest requestDigest = ctx.getMessageId() != null
                ? new DiscoverResponse.RequestDigest(ctx.getMessageId(), null)
                : null;
        response.setMessage(new DiscoverResponse.ResponseMessage(catalogs, requestDigest));

        if (!validateResponse(response)) {
            log.warn(LogEvent.RESPONSE_BUILD_VALIDATION_FAILED);
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
        log.error(LogEvent.RESPONSE_ERROR, error.getMessage(), error);
        return buildEmptyResponse(context);
    }
}
