package org.beckn.discover.service.engine;

import org.beckn.discover.model.Catalog;

import java.util.List;
import java.util.Optional;

/**
 * Contract for structured query engines that handle JSONPath filter and/or
 * PostGIS spatial queries against a catalog store.
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code PostgreSQLQueryEngine} — PostgreSQL / YugabyteDB with GIN + GiST
 *       indexes (current default)</li>
 *   <li>{@code ElasticsearchQueryEngine} — future full-text + vector hybrid
 *       (implement and wire via {@code @ConditionalOnProperty})</li>
 * </ul>
 *
 * <h3>Query paths</h3>
 * <pre>
 * Path B  —  {@link #executeFilterQuery}   : JSONPath only      (GIN index)
 * Path C  —  {@link #executeSpatialQuery}  : spatial only       (GiST index)
 * Path A  —  {@link #executeCombinedQuery} : filter + spatial   (single SQL round-trip)
 * </pre>
 *
 * <h3>Path A semantics</h3>
 * {@link #executeCombinedQuery} distinguishes between two distinct outcomes via
 * {@code Optional}:
 * <ul>
 *   <li>{@code Optional.empty()} — the engine could not construct a combined
 *       query (e.g. no valid spatial conditions).  The caller MUST fall back to
 *       the parallel B ∥ C approach.</li>
 *   <li>{@code Optional.of(emptyList)} — the query executed successfully but
 *       matched zero items.  The caller MUST NOT retry; this is a valid
 *       empty-result response.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All implementations must be stateless and safe for concurrent invocation.
 */
public interface QueryEngine {

    /**
     * Path B: executes a JSONPath filter query against the catalog store.
     *
     * @param request fully-populated query context; {@code request.hasFilters()}
     *                must be {@code true}
     * @return matching catalogs; never {@code null}, may be empty
     * @throws IllegalArgumentException if the filter expression is blank
     * @throws Exception                on transient infrastructure failure (may be retried by caller)
     */
    List<Catalog> executeFilterQuery(QueryRequest request) throws Exception;

    /**
     * Path C: executes a PostGIS spatial query against the pre-indexed
     * {@code item_location_collection} table.
     *
     * @param request fully-populated query context; {@code request.hasSpatial()}
     *                must be {@code true}
     * @return matching catalogs; never {@code null}, may be empty
     * @throws Exception on infrastructure failure
     */
    List<Catalog> executeSpatialQuery(QueryRequest request) throws Exception;

    /**
     * Path A: attempts a single-round-trip combined (filter + spatial) query.
     *
     * <p>Returns {@link Optional#empty()} when no valid spatial conditions could
     * be built — the caller MUST fall back to executing
     * {@link #executeFilterQuery} and {@link #executeSpatialQuery} in parallel
     * and intersecting the results.</p>
     *
     * <p>Returns {@code Optional.of(emptyList)} when the query executed but
     * matched nothing — callers must NOT fall back in this case.</p>
     *
     * @param request fully-populated query context; both
     *                {@code request.hasFilters()} and {@code request.hasSpatial()}
     *                must be {@code true}
     * @return {@link Optional#empty()} on build failure; otherwise matched catalogs
     * @throws Exception on infrastructure failure
     */
    Optional<List<Catalog>> executeCombinedQuery(QueryRequest request) throws Exception;
}
