package org.beckn.discover.logging;

/**
 * Canonical log event name constants for the catalog-discover-job.
 *
 * Every log statement in the job must use one of these constants as the
 * leading token of the message so that log aggregators can group, count,
 * and alert on specific event types without fragile substring matching.
 */
public final class LogEvent {

    private LogEvent() {}

    // ── HTTP entry points ─────────────────────────────────────────────────────
    public static final String REQUEST_RECEIVED        = "discover.request.received";
    public static final String AUTH_PASSED             = "discover.auth.passed";
    public static final String AUTH_FAILED             = "discover.auth.failed";
    public static final String AUTH_SKIPPED            = "discover.auth.skipped";
    public static final String AUTH_DISABLED           = "discover.auth.disabled";
    public static final String AUTH_VERIFY_START       = "discover.auth.verify.start";
    public static final String AUTH_VERIFY_DONE        = "discover.auth.verify.done";
    public static final String VALIDATE_PASSED         = "discover.validate.passed";
    public static final String VALIDATE_FAILED         = "discover.validate.failed";
    public static final String KAFKA_QUEUED            = "discover.kafka.queued";
    public static final String KAFKA_QUEUE_FAILED      = "discover.kafka.queue.failed";
    public static final String NACK_RESPONSE           = "discover.nack.response";

    // ── Kafka consumer ────────────────────────────────────────────────────────
    public static final String CONSUMER_RECEIVED       = "consumer.received";
    public static final String CONSUMER_PARSE_FAILED   = "consumer.parse.failed";
    public static final String CONSUMER_VALIDATE_FAILED = "consumer.validate.failed";

    // ── Query engine ──────────────────────────────────────────────────────────
    public static final String QUERY_STARTED           = "query.started";
    public static final String QUERY_COMPLETED         = "query.completed";
    public static final String QUERY_FAILED            = "query.failed";
    public static final String QUERY_TIMEOUT           = "query.timeout";

    // ── Response pipeline ─────────────────────────────────────────────────────
    public static final String PIPELINE_COMPLETED      = "pipeline.completed";
    public static final String RESPONSE_BUILT          = "response.built";
    public static final String RESPONSE_PUBLISHED      = "response.published";
    public static final String RESPONSE_PUBLISH_FAILED = "response.publish.failed";

    // ── Elasticsearch ─────────────────────────────────────────────────────────
    public static final String ES_SEARCH_STARTED       = "es.search.started";
    public static final String ES_SEARCH_COMPLETED     = "es.search.completed";
    public static final String ES_SEARCH_FAILED        = "es.search.failed";

    // ── NLWeb ─────────────────────────────────────────────────────────────────
    public static final String NLWEB_SEARCH_STARTED    = "nlweb.search.started";
    public static final String NLWEB_SEARCH_COMPLETED  = "nlweb.search.completed";
    public static final String NLWEB_SEARCH_FAILED     = "nlweb.search.failed";

    // ── Schema context ES push-down ───────────────────────────────────────────
    public static final String ES_SCHEMA_FILTER_APPLIED = "es.schema.filter.applied";
    public static final String ES_SCHEMA_FILTER_SKIPPED = "es.schema.filter.skipped";

    // ── Metrics ───────────────────────────────────────────────────────────────
    public static final String METRICS_FAILURE       = "discovery.metrics.failure";
    public static final String METRICS_UNKNOWN_ENGINE = "discovery.metrics.unknown_engine";
    public static final String METRICS_RESET         = "discovery.metrics.reset";

    // ── Response assembler ────────────────────────────────────────────────────
    public static final String RESPONSE_BUILD_EMPTY           = "response.build.empty";
    public static final String RESPONSE_BUILD_VALIDATION_FAILED = "response.build.validation_failed";
    public static final String RESPONSE_ERROR                 = "response.error";

    // ── PostgreSQL assembler ──────────────────────────────────────────────────
    public static final String ASSEMBLER_EMPTY             = "assembler.empty";
    public static final String ASSEMBLER_START             = "assembler.start";
    public static final String ASSEMBLER_DONE              = "assembler.done";
    public static final String ASSEMBLER_ROW_ERROR         = "assembler.row.error";
    public static final String ASSEMBLER_ROW_SKIP          = "assembler.row.skip";
    public static final String ASSEMBLER_CATALOG_ATTR_ERROR = "assembler.catalog.attributes.error";
    public static final String ASSEMBLER_OFFER_SKIP        = "assembler.offer.skip";
    public static final String ASSEMBLER_JSON_PARSE_FAILED = "assembler.json.parse.failed";
    public static final String ASSEMBLER_FIELD_PARSE_FAILED = "assembler.field.parse.failed";

    // ── Query routing ─────────────────────────────────────────────────────────
    public static final String QUERY_PATH_SELECTED      = "query.path.selected";
    public static final String QUERY_PATH_FALLBACK      = "query.path.fallback";
    public static final String QUERY_INTERSECT_EMPTY    = "query.intersect.empty";
    public static final String QUERY_INTERSECT_DONE     = "query.intersect.done";
    public static final String QUERY_PARALLEL_DONE      = "query.parallel.done";

    // ── Embedding ─────────────────────────────────────────────────────────────
    public static final String EMBEDDING_CLIENT_INIT    = "embedding-client.init";
    public static final String EMBEDDING_RETRY          = "embedding.retry";
    public static final String EMBEDDING_ATTEMPT_FAILED = "embedding.attempt.failed";
    public static final String EMBEDDING_FAILED         = "embedding.failed.all-attempts";
    public static final String EMBEDDING_EMPTY          = "embedding.empty";

    // ── PostgreSQL query executor ─────────────────────────────────────────────
    public static final String QUERY_EXECUTOR_INIT         = "queryExecutor.init";
    public static final String QUERY_EXECUTOR_SHUTDOWN     = "queryExecutor.shutdown";
    public static final String QUERY_EXECUTOR_SHUTDOWN_TIMEOUT = "queryExecutor.shutdown.timeout";

    // ── PostgreSQL service ────────────────────────────────────────────────────
    public static final String JSONPATH_QUERY_START   = "jsonpath.query.start";
    public static final String SPATIAL_QUERY_START    = "spatial.query.start";
    public static final String SPATIAL_QUERY_SKIP     = "spatial.query.skip";
    public static final String COMBINED_QUERY_START   = "combined.query.start";
    public static final String COMBINED_QUERY_SKIP    = "combined.query.skip";

    // ── Spatial query builder ─────────────────────────────────────────────────
    public static final String SPATIAL_BUILD_SKIP      = "spatial.build.skip";
    public static final String SPATIAL_BUILD_DONE      = "spatial.build.done";
    public static final String SPATIAL_COMBINED_SKIP   = "spatial.combined.skip";
    public static final String SPATIAL_COMBINED_BUILT  = "spatial.combined.built";
    public static final String SPATIAL_CONDITION_SKIP  = "spatial.condition.skip";
    public static final String SPATIAL_CONDITION_ADDED = "spatial.condition.added";

    // ── Catalog pipeline ──────────────────────────────────────────────────────
    public static final String PIPELINE_EMPTY              = "pipeline.empty";
    public static final String PIPELINE_STEP1_SKIPPED      = "pipeline.step1.skipped";
    public static final String PIPELINE_STEP1_SCHEMA_FILTER = "pipeline.step1.schemaFilter";
    public static final String PIPELINE_STEP5_REMOVED_EMPTY = "pipeline.step5.removedEmptyCatalog";
    public static final String PIPELINE_STEP5_REMOVED      = "pipeline.step5.removed";

    // ── Catalog processor ─────────────────────────────────────────────────────
    public static final String CATALOG_PROCESS_SKIP    = "catalog.process.skip";
    public static final String RESOURCE_PROCESS_SKIP   = "resource.process.skip";
    public static final String PROVIDER_PROCESS_SKIP   = "provider.process.skip";
    public static final String CATALOG_MERGE_ERROR     = "catalog.merge.error";
    public static final String CATALOG_MERGE_DONE      = "catalog.merge.done";
    public static final String CATALOG_OFFER_FILTER    = "catalog.offerFilter";
    public static final String CATALOG_VALIDATE_FAIL   = "catalog.validate.fail";
    public static final String RESOURCE_VALIDATE_FAIL  = "resource.validate.fail";

    // ── NLWeb assembler ───────────────────────────────────────────────────────
    public static final String NLWEB_ITEM_SKIPPED = "nlweb.item.skipped";

    // ── Query enricher ────────────────────────────────────────────────────────
    public static final String QUERY_ENRICHER_INIT          = "query-enricher.init";
    public static final String QUERY_ENRICHER_RAW           = "query-enricher.raw";
    public static final String QUERY_ENRICHER_FAILED        = "query-enricher.failed";
    public static final String QUERY_ENRICHER_HTTP_ERROR    = "query-enricher.http-error";
    public static final String QUERY_ENRICHER_EMPTY_RESPONSE = "query-enricher.empty-response";
    public static final String QUERY_ENRICHER_ENRICHED      = "query-enricher.enriched";
    public static final String QUERY_ENRICHER_PARSE_FAILED  = "query-enricher.parse-failed";

    // ── Provider offer enrichment ────────────────────────────────────────────
    public static final String PROVIDER_OFFER_ENRICHED = "provider.offer.enriched";

    // ── Elasticsearch query engine ────────────────────────────────────────────
    public static final String ES_ENGINE_SPATIAL_START          = "es.engine.spatial.start";
    public static final String ES_ENGINE_SPATIAL_SKIP           = "es.engine.spatial.skip";
    public static final String ES_ENGINE_SPATIAL_EMPTY_VECTOR   = "es.engine.spatial.empty-vector";
    public static final String ES_ENGINE_SPATIAL_REQUEST        = "es.engine.spatial.request";
    public static final String ES_ENGINE_SPATIAL_DONE           = "es.engine.spatial.done";
    public static final String ES_ENGINE_SPATIAL_INDEX_NOT_FOUND = "es.engine.spatial.index-not-found";
    public static final String ES_ENGINE_SPATIAL_UNKNOWN_FIELD  = "es.engine.spatial.unknown-field";

    // ── ES spatial query builder ──────────────────────────────────────────────
    public static final String ES_SPATIAL_UNSUPPORTED_OP         = "es.spatial.unsupported-op";
    public static final String ES_SPATIAL_UNSUPPORTED_QUANTIFIER = "es.spatial.unsupported-quantifier";
    public static final String ES_SPATIAL_NO_VALID_TARGET        = "es.spatial.no-valid-target";
    public static final String ES_SPATIAL_BUILD_QUERY_FAILED     = "es.spatial.build-query.failed";
}
