package org.beckn.discover.service.nlweb;

import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.NLWebService;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@link TextSearchEngine} implementation backed by NLWeb.
 *
 * <p>This bean is active by default (when
 * {@code discovery.text-search.engine} is absent or set to {@code nlweb}).
 * To switch to Elasticsearch for text search, set:</p>
 * <pre>
 * discovery:
 *   text-search:
 *     engine: elasticsearch
 * </pre>
 * <p>and provide an {@code ElasticsearchTextSearchEngine} bean annotated with
 * {@code @ConditionalOnProperty(…, havingValue="elasticsearch")}.
 * No code changes in {@code DiscoveryService} are required.</p>
 *
 * <h3>Processing flow (Path D)</h3>
 * <ol>
 *   <li>{@link NLWebService#queryNLWeb} — HTTP call to the NLWeb API
 *       (with {@code @Retryable}).</li>
 *   <li>{@link NLWebAssembler#assemble} — JSON → {@code List<Catalog>}
 *       (score filtering, catalog normalisation, provider-based merging).</li>
 * </ol>
 *
 * <p>The assembled catalogs are passed to the
 * {@link org.beckn.discover.service.response.CatalogPipeline} by the caller;
 * this engine does not run the pipeline itself.</p>
 *
 * <h3>KNOWN LIMITATION — {@code ?active}/{@code ?validity} filters are NOT applied on this path</h3>
 * <p>The active/validity value-match filters are enforced in-query by the PostgreSQL and
 * Elasticsearch engines against the {@code isActive}/{@code validity} (PG) and
 * {@code catalog_is_active}/{@code catalog_validity} (ES) fields. NLWeb delegates text search to an
 * external service that has no notion of those fields, so a text-only request routed to NLWeb
 * <b>cannot</b> honor {@code ?active}/{@code ?validity} — it emits a WARN
 * ({@code event=nlweb.active-filter-not-applied}) and returns results unfiltered by
 * activity/validity. Deployments that require the filter guarantee on text search must use
 * {@code discovery.text-search.engine=elasticsearch}. (Filtering still applies to the J / G / J+G
 * routes even under NLWeb, since those run on PostgreSQL.)</p>
 */
@Service
@ConditionalOnProperty(
        name         = "discovery.text-search.engine",
        havingValue  = "nlweb",
        matchIfMissing = true   // NLWeb is the default; active unless overridden
)
public class NLWebTextSearchEngine implements TextSearchEngine {

    private static final Logger log     = LoggerFactory.getLogger(NLWebTextSearchEngine.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final NLWebService   nlWebService;
    private final NLWebAssembler assembler;

    public NLWebTextSearchEngine(NLWebService nlWebService, NLWebAssembler assembler) {
        this.nlWebService = nlWebService;
        this.assembler    = assembler;
    }

    // ── TextSearchEngine impl ────────────────────────────────────────────────

    /**
     * Path D: queries NLWeb and assembles the response into catalogs.
     *
     * @throws IllegalArgumentException if {@code text} is blank
     * @throws Exception                on NLWeb API or assembly failure
     */
    @Override
    public List<Catalog> search(String text, QueryRequest queryRequest) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text search query cannot be null or empty");
        }

        String txId = queryRequest.transactionId();
        log.info(LogEvent.NLWEB_SEARCH_STARTED,
                value("transactionId", txId),
                value("query", text));
        // The active/validity value-match filters are applied in-query by the PostgreSQL and
        // Elasticsearch engines only. NLWeb delegates to an external service with no such field
        // support, so the flags are intentionally NOT applied on this path.
        if (queryRequest.hasActiveMatch() || queryRequest.hasValidMatch()) {
            log.warn("event=nlweb.active-filter-not-applied transactionId={} reason=nlweb-engine-has-no-active-validity-support", txId);
        }
        Instant start = Instant.now();

        try {
            String rawResponse = nlWebService.queryNLWeb(text);
            List<Catalog> catalogs = assembler.assemble(rawResponse, txId);

            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info(LogEvent.NLWEB_SEARCH_COMPLETED,
                    value("catalogs", catalogs.size()),
                    value("durationMs", ms),
                    value("transactionId", txId));
            perfLog.info(LogEvent.NLWEB_SEARCH_COMPLETED,
                    value("durationMs", ms),
                    value("catalogs", catalogs.size()),
                    value("transactionId", txId));

            return catalogs;

        } catch (IllegalArgumentException e) {
            throw e; // validation errors propagate without wrapping
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.error(LogEvent.NLWEB_SEARCH_FAILED,
                    value("durationMs", ms),
                    value("transactionId", txId),
                    value("error", e.getMessage()),
                    e);
            throw new Exception("NLWeb text search failed for transactionId=" + txId, e);
        }
    }
}
