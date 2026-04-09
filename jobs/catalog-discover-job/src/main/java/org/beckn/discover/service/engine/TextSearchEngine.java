package org.beckn.discover.service.engine;

import org.beckn.discover.model.Catalog;

import java.util.List;

/**
 * Contract for natural-language / full-text / semantic search engines.
 *
 * <p><b>Implementations (Path D):</b></p>
 * <ul>
 *   <li>{@code NLWebTextSearchEngine} — NLWeb integration (current default,
 *       activated when {@code discovery.text-search.engine=nlweb} or absent)</li>
 *   <li>{@code ElasticsearchTextSearchEngine} — Elasticsearch / OpenSearch hybrid
 *       (activate via {@code discovery.text-search.engine=elasticsearch})</li>
 * </ul>
 *
 * <h3>Swapping engines</h3>
 * Each implementation is annotated with
 * {@code @ConditionalOnProperty(name="discovery.text-search.engine", havingValue="…")}.
 * Switching the search backend requires only a YAML property change and a
 * restart — no Java source changes.
 *
 * <h3>Thread safety</h3>
 * All implementations must be stateless and safe for concurrent invocation.
 */
public interface TextSearchEngine {

    /**
     * Path D: executes a text / semantic search and returns matching catalogs.
     *
     * @param text    natural-language query string; must not be blank
     * @param context surrounding query context for schema filtering and logging
     * @return matching catalogs; never {@code null}, may be empty
     * @throws IllegalArgumentException if {@code text} is blank
     * @throws Exception                on transient infrastructure failure (may be retried by caller)
     */
    List<Catalog> search(String text, QueryRequest context) throws Exception;

    /**
     * Returns {@code true} when this engine natively applies schema context
     * filtering inside its ES/PG queries, so the pipeline step that post-filters
     * by schema context can be safely skipped.
     *
     * <p>Default: {@code false} — NLWeb relies on
     * {@link org.beckn.discover.service.response.CatalogPipeline} step 1 for
     * schema filtering. Override to {@code true} in Elasticsearch-backed
     * implementations.</p>
     */
    default boolean appliesSchemaFilter() {
        return false;
    }
}
