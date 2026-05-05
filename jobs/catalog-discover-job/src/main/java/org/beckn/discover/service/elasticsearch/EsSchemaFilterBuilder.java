package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.util.DiscoveryServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Stateless utility that builds paired (context, type) Elasticsearch filter
 * queries from a {@link QueryRequest}'s schema context data.
 *
 * <h3>Input model</h3>
 * {@code QueryRequest} stores schema context as two pre-split lists:
 * <ul>
 *   <li>{@code schemaContextUrls()} — base URLs without fragment
 *       (e.g. {@code "https://schema.org/Product"})</li>
 *   <li>{@code schemaTypes()} — fragment values
 *       (e.g. {@code "GroceryItem"})</li>
 * </ul>
 *
 * <h3>Paired tuple matching</h3>
 * A simple independent {@code terms} filter on context and type separately
 * would allow cross-matches between schema pairs with different base URLs.
 * This builder uses paired matching via {@code bool.should} with one
 * {@code bool.must} per pair. Pairing is derived from the raw schemaContext
 * URL list ({@link #buildSchemaFilters(List, String)}) to preserve exact
 * context+type correspondence.
 *
 * <pre>
 * rawUrls: ["https://schema.org/Product#GroceryItem",
 *           "https://beckn.org/Mobility#RideService"]
 * →
 * bool.should [
 *   bool.must [ term(context=https://schema.org/Product), term(type=GroceryItem) ]
 *   bool.must [ term(context=https://beckn.org/Mobility), term(type=RideService) ]
 * ]
 * </pre>
 *
 * <h3>URL without fragment</h3>
 * When a schemaContext URL has no fragment (e.g. {@code https://schema.org}),
 * only the context term is used — no type filter for that pair.
 *
 * <h3>No-op when empty</h3>
 * Returns an empty list when the URL list is null or empty — callers can check
 * {@code List.isEmpty()} to skip filter injection.
 *
 * <h3>Thread safety</h3>
 * This class has no state and is safe for concurrent use.
 */
public final class EsSchemaFilterBuilder {

    private static final Logger log = LoggerFactory.getLogger(EsSchemaFilterBuilder.class);

    static final String FIELD_CONTEXT = "resource_attributes_context";
    static final String FIELD_TYPE    = "resource_attributes_type";

    private EsSchemaFilterBuilder() {
        // utility class — no instances
    }

    /**
     * Convenience overload that reads raw schemaContext URLs from the request's
     * pre-split lists and rebuilds paired filters.
     *
     * <p><b>Limitation:</b> because {@code QueryRequest} stores pre-split base URLs
     * and type fragments separately (pairing is not preserved), this overload
     * builds context-only or type-augmented pairs that are correct for the
     * common case where each base URL has a single corresponding type. For callers
     * that have the raw URL list available, prefer
     * {@link #buildSchemaFilters(List, String)}.</p>
     *
     * @param req the query request; must not be null
     * @return list of filter queries; empty when no schema filter requested
     */
    public static List<Query> buildSchemaFilters(QueryRequest req) {
        // Use raw schemaContext URLs to preserve exact (context, type) pairing.
        // The pre-split schemaContextUrls/schemaTypes lose pairing due to HashSet
        // deduplication in extractSchemaContextParts.
        var rawUrls = req.rawSchemaContextUrls();

        if (rawUrls.isEmpty()) {
            log.debug(LogEvent.ES_SCHEMA_FILTER_SKIPPED,
                    value("transactionId", req.transactionId()));
            return List.of();
        }

        return buildSchemaFilters(rawUrls, req.transactionId());
    }

    /**
     * Builds paired (context, type) ES filter queries from raw schemaContext URLs.
     * Each URL is split into base URL (context) and optional fragment (type).
     * Returns a single-element list containing a {@code bool.should} with one
     * {@code bool.must} pair per URL.
     *
     * <p>Returns an empty list when {@code rawSchemaContextUrls} is null or empty.
     * Handles unbounded cardinality (many URLs in a single should clause).</p>
     *
     * @param rawSchemaContextUrls raw URLs optionally containing {@code #fragment}
     * @param transactionId        for structured logging
     * @return list of filter queries to inject into ES queries; empty when no schema filter
     */
    public static List<Query> buildSchemaFilters(List<String> rawSchemaContextUrls,
                                                  String transactionId) {
        if (rawSchemaContextUrls == null || rawSchemaContextUrls.isEmpty()) {
            return List.of();
        }

        List<Query> pairs = new ArrayList<>(rawSchemaContextUrls.size());

        for (String schemaUrl : rawSchemaContextUrls) {
            if (DiscoveryServiceUtil.isBlank(schemaUrl)) continue;

            String base     = DiscoveryServiceUtil.extractBaseUrl(schemaUrl);
            String fragment = DiscoveryServiceUtil.extractFragment(schemaUrl);

            if (DiscoveryServiceUtil.isBlank(base)) continue;

            if (DiscoveryServiceUtil.isBlank(fragment)) {
                // Context-only filter: no type restriction for this pair
                Query contextOnly = Query.of(q -> q.term(t -> t
                        .field(FIELD_CONTEXT)
                        .value(base)));
                pairs.add(contextOnly);
            } else {
                // Paired context + type filter: both must match the same document
                String resolvedBase     = base;
                String resolvedFragment = fragment;
                Query pair = Query.of(q -> q.bool(b -> b
                        .must(Query.of(mq -> mq.term(t -> t
                                .field(FIELD_CONTEXT)
                                .value(resolvedBase))))
                        .must(Query.of(mq -> mq.term(t -> t
                                .field(FIELD_TYPE)
                                .value(resolvedFragment))))));
                pairs.add(pair);
            }
        }

        if (pairs.isEmpty()) {
            return List.of();
        }

        // Wrap all pairs in a bool.should so any matching pair qualifies the document
        Query schemaFilter = Query.of(q -> q.bool(b -> {
            pairs.forEach(b::should);
            b.minimumShouldMatch("1");
            return b;
        }));

        log.debug(LogEvent.ES_SCHEMA_FILTER_APPLIED,
                value("schemaContextCount", rawSchemaContextUrls.size()),
                value("pairsBuilt", pairs.size()),
                value("transactionId", transactionId));

        return List.of(schemaFilter);
    }
}
