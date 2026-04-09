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
}
