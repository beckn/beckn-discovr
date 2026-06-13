package org.beckn.discover.service.postgresql;

import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.logging.LogMessages;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link QueryEngine} implementation backed by PostgreSQL / YugabyteDB.
 *
 * <p>Orchestrates the two-layer pipeline for every query path:</p>
 * <ol>
 *   <li><b>Query</b> — {@link PostgreSQLService} builds and executes JDBC
 *       statements, returning raw {@code List<Map<String,Object>>} rows.</li>
 *   <li><b>Assembly</b> — {@link PostgreSQLAssembler} transforms rows into
 *       a list of partially-assembled {@link Catalog} objects.</li>
 * </ol>
 *
 * <p>The assembled catalogs are <em>not</em> post-processed here; the
 * {@link org.beckn.discover.service.response.CatalogPipeline} in
 * {@code DiscoveryService} applies the shared normalization, deduplication,
 * and filtering steps that are common across all engine types.</p>
 *
 * <h3>Path A semantics (combined query)</h3>
 * <p>{@link #executeCombinedQuery} returns {@link Optional#empty()} when the
 * underlying {@link PostgreSQLService} signals that no spatial conditions could
 * be built — for a well-formed J+G request this should not occur, and the
 * caller raises an error. {@code Optional.of(emptyList)} is a valid
 * "no results" response.</p>
 */
@Service
public class PostgreSQLQueryEngine implements QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLQueryEngine.class);

    private final PostgreSQLService   queryService;
    private final PostgreSQLAssembler assembler;

    public PostgreSQLQueryEngine(PostgreSQLService queryService, PostgreSQLAssembler assembler) {
        this.queryService = queryService;
        this.assembler    = assembler;
    }

    // ── QueryEngine impl ─────────────────────────────────────────────────────

    /**
     * Path B: JSONPath filter query → assembled catalogs.
     */
    @Override
    public List<Catalog> executeFilterQuery(QueryRequest request) throws Exception {
        Instant start = Instant.now();
        log.debug(LogEvent.QUERY_STARTED + ".filter", value("transactionId", request.transactionId()));

        List<Map<String, Object>> rows = queryService.executeJsonPathQuery(request);
        List<Catalog> catalogs = assembler.assemble(rows, request);

        log.info(LogEvent.QUERY_COMPLETED + ".filter",
                value("catalogs", catalogs.size()),
                value("durationMs", elapsed(start)),
                value("transactionId", request.transactionId()));
        return catalogs;
    }

    /**
     * Path C: PostGIS spatial query → assembled catalogs.
     */
    @Override
    public List<Catalog> executeSpatialQuery(QueryRequest request) throws Exception {
        Instant start = Instant.now();
        log.debug(LogEvent.QUERY_STARTED + ".spatial", value("transactionId", request.transactionId()));

        List<Map<String, Object>> rows = queryService.executeSpatialQuery(request);
        List<Catalog> catalogs = assembler.assemble(rows, request);

        log.info(LogEvent.QUERY_COMPLETED + ".spatial",
                value("catalogs", catalogs.size()),
                value("durationMs", elapsed(start)),
                value("transactionId", request.transactionId()));
        return catalogs;
    }

    /**
     * Path A: combined filter + spatial query in a single SQL round-trip.
     *
     * <p>Returns {@link Optional#empty()} when no spatial conditions could be
     * built (caller raises an error). Returns {@code Optional.of(emptyList)}
     * when the query ran successfully but matched nothing.</p>
     */
    @Override
    public Optional<List<Catalog>> executeCombinedQuery(QueryRequest request) throws Exception {
        Instant start = Instant.now();
        log.debug(LogEvent.QUERY_STARTED + ".combined", value("transactionId", request.transactionId()));

        Optional<List<Map<String, Object>>> rowsOpt = queryService.executeCombinedQuery(request);

        if (rowsOpt.isEmpty()) {
            log.info(LogEvent.QUERY_COMPLETED + ".combined-skip",
                    value("reason", LogMessages.REASON_NO_SPATIAL_CONDITIONS),
                    value("method", "spatialQuery"),
                    value("transactionId", request.transactionId()));
            return Optional.empty();
        }

        List<Catalog> catalogs = assembler.assemble(rowsOpt.get(), request);

        log.info(LogEvent.QUERY_COMPLETED + ".combined",
                value("catalogs", catalogs.size()),
                value("durationMs", elapsed(start)),
                value("transactionId", request.transactionId()));
        return Optional.of(catalogs);
    }

    // ── JSONPath query restricted to a set of resource IDs (case 6 J+T) ───────

    /**
     * Step 2 of the JSONPath+text query for case 6 (J+T): runs a JSONPath query
     * restricted to the given set of resource IDs (returned by ES step 1).
     *
     * <p>The IDs are passed as a SQL {@code ANY(?)} parameter; {@code ORDER BY
     * array_position(?, i.id)} in the underlying query preserves ES relevance order.</p>
     *
     * @param resourceIds non-null, non-empty collection of resource IDs from ES step 1
     * @return assembled catalogs (may be empty if no rows pass the JSONPath filter)
     */
    public List<Catalog> executeJsonPathQueryByResourceIds(QueryRequest request,
            Collection<String> resourceIds) throws Exception {
        Instant start = Instant.now();
        log.debug(LogEvent.QUERY_STARTED + ".jsonpath-by-ids",
                value("transactionId", request.transactionId()),
                value("resourceIdsCount", resourceIds.size()));

        List<Map<String, Object>> rows = queryService.executeJsonPathChainQuery(request, resourceIds);
        List<Catalog> catalogs = assembler.assemble(rows, request);

        log.info(LogEvent.QUERY_COMPLETED + ".jsonpath-by-ids",
                value("catalogs", catalogs.size()),
                value("durationMs", elapsed(start)),
                value("transactionId", request.transactionId()));
        return catalogs;
    }

    // ── JSONPath + spatial restricted to a set of resource IDs (case 7 J+G+T) ─

    /**
     * Step 2 of the JSONPath+text query for case 7 (J+G+T): runs a combined
     * JSONPath + spatial query restricted to the given set of resource IDs
     * (returned by ES step 1).
     *
     * <p>Spatial is re-applied here belt-and-suspenders style, even though ES
     * step 1 already filtered by geo. Falls back via {@link Optional#empty} when
     * PSQL spatial conditions cannot be built — caller should then degrade to
     * {@link #executeJsonPathQueryByResourceIds}.</p>
     *
     * @param resourceIds non-null, non-empty collection of resource IDs from ES step 1
     * @return Optional.empty() when no spatial conditions could be built; otherwise
     *         assembled catalogs (may be empty)
     */
    public Optional<List<Catalog>> executeJsonPathAndSpatialQueryByResourceIds(QueryRequest request,
            Collection<String> resourceIds) throws Exception {
        Instant start = Instant.now();
        log.debug(LogEvent.QUERY_STARTED + ".jsonpath-spatial-by-ids",
                value("transactionId", request.transactionId()),
                value("resourceIdsCount", resourceIds.size()));

        Optional<List<Map<String, Object>>> rowsOpt =
                queryService.executeJsonPathChainWithSpatial(request, resourceIds);

        if (rowsOpt.isEmpty()) {
            log.info(LogEvent.QUERY_COMPLETED + ".jsonpath-spatial-by-ids.skip",
                    value("reason", LogMessages.REASON_NO_SPATIAL_CONDITIONS),
                    value("transactionId", request.transactionId()));
            return Optional.empty();
        }

        List<Catalog> catalogs = assembler.assemble(rowsOpt.get(), request);
        log.info(LogEvent.QUERY_COMPLETED + ".jsonpath-spatial-by-ids",
                value("catalogs", catalogs.size()),
                value("durationMs", elapsed(start)),
                value("transactionId", request.transactionId()));
        return Optional.of(catalogs);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }
}
