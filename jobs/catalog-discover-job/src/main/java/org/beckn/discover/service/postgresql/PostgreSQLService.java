package org.beckn.discover.service.postgresql;

import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathQueryBuilder;
import org.beckn.discover.service.postgresql.spatial.SpatialQueryBuilder;
import org.beckn.discover.config.DiscoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Low-level JDBC execution layer for PostgreSQL / YugabyteDB.
 *
 * <p>This class is an internal component of the {@code postgresql} package.
 * External code must go through {@link PostgreSQLQueryEngine} which implements
 * the {@link org.beckn.discover.service.engine.QueryEngine} interface and
 * handles row-to-catalog assembly.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Build {@link QueryBuilderHelper.QuerySpec} objects via the builder helpers.</li>
 *   <li>Execute them against the database using {@link JdbcTemplate}.</li>
 *   <li>Log timing and row counts (dot-notation keys, per NFR-3.1/NFR-3.2).</li>
 *   <li>Apply {@code @Retryable} on JSONPath queries (NFR-4.1).</li>
 *   <li>Run EXPLAIN ANALYZE when the {@code log-explain-analyze} property is set.</li>
 * </ul>
 *
 * <h3>SQL injection prevention</h3>
 * <p>All query text is composed from compile-time constants in
 * {@link QueryBuilderHelper}.  Every user-supplied value flows through a JDBC
 * {@code ?} bind parameter — never interpolated into SQL text (NFR-2.1/NFR-2.2).</p>
 *
 * <h3>Return types</h3>
 * <ul>
 *   <li>{@link #executeJsonPathQuery} and {@link #executeSpatialQuery} return
 *       {@code List<Map<String,Object>>} — an empty list for "no results".</li>
 *   <li>{@link #executeCombinedQuery} returns {@code Optional<List>}:
 *       {@link Optional#empty()} means "no spatial conditions could be built";
 *       {@code Optional.of(emptyList)} means "query ran, zero results found".</li>
 * </ul>
 */
@Service
public class PostgreSQLService {

    private static final Logger log      = LoggerFactory.getLogger(PostgreSQLService.class);
    private static final Logger perfLog  = LoggerFactory.getLogger("org.beckn.discover.performance");

    private static final int MAX_RETRIES = 3;

    private final JdbcClient             jdbcClient;
    private final JsonPathQueryBuilder   jsonPathQueryBuilder;
    private final SpatialQueryBuilder    spatialQueryBuilder;
    private final DiscoveryProperties    discoveryProperties;

    public PostgreSQLService(
            JdbcClient           jdbcClient,
            JsonPathQueryBuilder jsonPathQueryBuilder,
            SpatialQueryBuilder  spatialQueryBuilder,
            DiscoveryProperties  discoveryProperties) {
        this.jdbcClient           = jdbcClient;
        this.jsonPathQueryBuilder = jsonPathQueryBuilder;
        this.spatialQueryBuilder  = spatialQueryBuilder;
        this.discoveryProperties  = discoveryProperties;
    }

    // ── Path B: JSONPath filter ──────────────────────────────────────────────

    /**
     * Executes a JSONPath filter query against the {@code item} table.
     *
     * <p>Automatically retried up to {@value MAX_RETRIES} times with 1-second
     * backoff for transient failures.  {@link IllegalArgumentException} (blank
     * filter) bypasses the retry budget — NFR-4.1 / NFR-4.2.</p>
     *
     * @throws IllegalArgumentException if {@code request.filters()} is blank
     */
    @Retryable(
        value     = { TransientDataAccessException.class },
        exclude   = { IllegalArgumentException.class, NonTransientDataAccessException.class },
        maxAttempts = MAX_RETRIES,
        backoff   = @Backoff(delay = 1000)
    )
    public List<Map<String, Object>> executeJsonPathQuery(QueryRequest request) throws Exception {
        if (!request.hasFilters()) {
            throw new IllegalArgumentException("Filter expression cannot be null or empty");
        }
        log.debug("event={}", LogEvent.JSONPATH_QUERY_START);
        QueryBuilderHelper.QuerySpec query = jsonPathQueryBuilder.build(
                request.filters(),
                request.rawSchemaContextUrls(),
                resultLimit(),
                request.networkId(),
                request.filterType());
        return executeQuery(query, "jsonpath", request.transactionId(), "PostgreSQL JSONPath query failed");
    }

    // ── Path C: Spatial ──────────────────────────────────────────────────────

    /**
     * Executes a PostGIS spatial query via {@code item_location_collection}.
     *
     * <p>All geometry and distance values are bound as JDBC {@code ?}
     * parameters.  Unsupported operations are silently skipped and logged at
     * WARN — NFR-4.3.</p>
     *
     * @return matched item rows, or an empty list when no valid spatial
     *         constraints were present or matched
     */
    public List<Map<String, Object>> executeSpatialQuery(QueryRequest request) throws Exception {
        log.debug("event={}", LogEvent.SPATIAL_QUERY_START);
        Optional<QueryBuilderHelper.QuerySpec> queryOpt = spatialQueryBuilder.build(
                request.spatial(),
                request.rawSchemaContextUrls(),
                resultLimit(),
                request.networkId());
        if (queryOpt.isEmpty()) {
            log.debug("event={} reason=no-conditions", LogEvent.SPATIAL_QUERY_SKIP);
            return new ArrayList<>();
        }
        return executeQuery(queryOpt.get(), "spatial", request.transactionId(), "Spatial query failed");
    }

    // ── Chain step 2: JSONPath with ID allowlist (case 6) ────────────────────

    /**
     * Executes a JSONPath filter query restricted to resources whose ID is in
     * {@code idAllowlist}.
     *
     * <p>Called as chain step 2 for case 6 (JSONPath + text). The allowlist comes
     * from ES step 1 (top-K resource IDs by text relevance).  ORDER BY preserves
     * ES relevance order via {@code array_position(?, i.id)}.</p>
     *
     * @param idAllowlist non-null, non-empty collection of resource IDs
     */
    public List<Map<String, Object>> executeJsonPathChainQuery(QueryRequest request,
            java.util.Collection<String> idAllowlist) throws Exception {
        if (!request.hasFilters()) {
            throw new IllegalArgumentException("Filter expression cannot be null or empty");
        }
        log.debug("event={} allowlistSize={}", LogEvent.JSONPATH_QUERY_START + ".chain", idAllowlist.size());
        QueryBuilderHelper.QuerySpec query = jsonPathQueryBuilder.buildWithAllowlist(
                request.filters(),
                request.rawSchemaContextUrls(),
                resultLimit(),
                idAllowlist,
                request.networkId(),
                request.filterType());
        return executeQuery(query, "jsonpath-chain", request.transactionId(), "PostgreSQL chain JSONPath query failed");
    }

    // ── Chain step 2: JSONPath + spatial with ID allowlist (case 7) ──────────

    /**
     * Executes a combined JSONPath + spatial filter restricted to resources whose
     * ID is in {@code idAllowlist}.
     *
     * <p>Called as chain step 2 for case 7 (JSONPath + spatial + text). The belt-
     * and-suspenders geo condition is applied here redundantly even though ES already
     * filtered by geo in step 1, to guarantee correctness when ES geo precision
     * deviates from PSQL PostGIS precision.</p>
     *
     * @param idAllowlist non-null, non-empty collection of resource IDs
     * @return Optional.empty() when no spatial conditions could be built (caller
     *         falls back to case-6 path); Optional.of(rows) otherwise.
     */
    public Optional<List<Map<String, Object>>> executeJsonPathChainWithSpatial(QueryRequest request,
            java.util.Collection<String> idAllowlist) throws Exception {
        if (!request.hasFilters()) {
            throw new IllegalArgumentException("Filter expression cannot be null or empty");
        }
        log.debug("event={} allowlistSize={}", LogEvent.COMBINED_QUERY_START + ".chain", idAllowlist.size());

        Optional<QueryBuilderHelper.QuerySpec> queryOpt = spatialQueryBuilder.buildCombinedWithAllowlist(
                request.spatial(),
                request.filters(),
                request.rawSchemaContextUrls(),
                resultLimit(),
                idAllowlist,
                request.networkId(),
                request.filterType());

        if (queryOpt.isEmpty()) {
            log.debug("event={} reason=no-spatial-conditions", LogEvent.COMBINED_QUERY_SKIP + ".chain");
            return Optional.empty();
        }
        List<Map<String, Object>> rows = executeQuery(queryOpt.get(), "jsonpath-chain-spatial",
                request.transactionId(), "PostgreSQL chain JSONPath+spatial query failed");
        return Optional.of(rows);
    }

    // ── Path A: Combined ─────────────────────────────────────────────────────

    /**
     * Attempts a single-round-trip combined (JSONPath + spatial) query (Path A).
     *
     * <p>Distinguishes two outcomes via {@link Optional}:</p>
     * <ul>
     *   <li>{@link Optional#empty()} — spatial conditions could not be built
     *       from the request; the caller should raise an error (a well-formed
     *       J+G request should always produce conditions).</li>
     *   <li>{@code Optional.of(emptyList)} — query ran successfully but
     *       returned zero rows; treat as a valid "no results" response.</li>
     * </ul>
     */
    public Optional<List<Map<String, Object>>> executeCombinedQuery(QueryRequest request) throws Exception {
        log.debug("event={}", LogEvent.COMBINED_QUERY_START);
        Optional<QueryBuilderHelper.QuerySpec> queryOpt = spatialQueryBuilder.buildCombined(
                request.spatial(),
                request.filters(),
                request.rawSchemaContextUrls(),
                resultLimit(),
                request.networkId(),
                request.filterType());
        if (queryOpt.isEmpty()) {
            log.debug("event={} reason=no-spatial-conditions", LogEvent.COMBINED_QUERY_SKIP);
            return Optional.empty();
        }
        List<Map<String, Object>> rows = executeQuery(queryOpt.get(), "combined", request.transactionId(),
                "Combined JSONPath + spatial query failed");
        return Optional.of(rows);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private int resultLimit() {
        return (discoveryProperties != null && discoveryProperties.getPostgresql() != null)
                ? discoveryProperties.getPostgresql().getResultLimit()
                : 100;
    }

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private List<Map<String, Object>> executeQuery(QueryBuilderHelper.QuerySpec query, String queryType,
            String transactionId, String errorMessage) throws Exception {
        Instant start = Instant.now();
        log.debug("{}.query.execute params={} transactionId={}", queryType, query.parameters().size(), transactionId);
        try {
            logExplainIfEnabled(query, queryType);
            List<Map<String, Object>> rows = jdbcClient.sql(query.sql())
                    .params(query.parameters())
                    .query()
                    .listOfRows();
            long ms = elapsed(start);
            log.info("{}.query.success rows={} durationMs={} transactionId={}",
                    queryType, rows.size(), ms, transactionId);
            perfLog.info("{}.query durationMs={} rows={} transactionId={}",
                    queryType, ms, rows.size(), transactionId);
            return rows;
        } catch (Exception e) {
            long ms = elapsed(start);
            log.error("{}.query.failed durationMs={} transactionId={} error={}",
                    queryType, ms, transactionId, e.getMessage(), e);
            throw new Exception(errorMessage, e);
        }
    }

    /**
     * Logs {@code EXPLAIN (ANALYZE, BUFFERS, VERBOSE)} for the given query
     * when the {@code discovery.postgresql.log-explain-analyze} property is
     * enabled.
     *
     * <p><b>Never enable in production</b> — EXPLAIN ANALYZE executes the
     * query for real and adds measurable overhead.</p>
     */
    private void logExplainIfEnabled(QueryBuilderHelper.QuerySpec query, String queryType) {
        if (discoveryProperties == null
                || discoveryProperties.getPostgresql() == null
                || !discoveryProperties.getPostgresql().isLogExplainAnalyze()) {
            return;
        }
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, VERBOSE) " + query.sql();
        try {
            List<Map<String, Object>> planRows = jdbcClient.sql(explainSql)
                    .params(query.parameters())
                    .query()
                    .listOfRows();
            String plan = planRows.stream()
                    .map(row -> row.values().stream().findFirst().orElse("").toString())
                    .collect(Collectors.joining("\n"));
            log.info("{}.explain.plan:\n{}", queryType, plan);
        } catch (Exception e) {
            log.warn("{}.explain.failed error={}", queryType, e.getMessage(), e);
        }
    }
}
