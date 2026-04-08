package org.beckn.catalogpublish.logging;

public final class LogEvent {
    private LogEvent() {}

    // Consumer lifecycle
    public static final String CONSUMER_RECEIVED  = "consumer.received";
    public static final String CONSUMER_PROCESSED = "consumer.processed";
    public static final String CONSUMER_ERROR     = "consumer.error";
    public static final String CONSUMER_REJECTED  = "consumer.rejected";

    // Parse step
    public static final String PARSE_COMPLETED = "parse.completed";
    public static final String PARSE_FAILED    = "parse.failed";

    // Validate step
    public static final String VALIDATE_PASSED = "validate.passed";
    public static final String VALIDATE_FAILED = "validate.failed";

    // Persistence step
    public static final String PERSIST_COMPLETED = "persist.completed";
    public static final String PERSIST_FAILED    = "persist.failed";

    // Elasticsearch indexing
    public static final String ES_INDEXED = "es.indexed";
    public static final String ES_FAILED  = "es.failed";

    // Kafka publish
    public static final String KAFKA_SENT   = "kafka.sent";
    public static final String KAFKA_FAILED = "kafka.failed";

    // HTTP push endpoint
    public static final String PUSH_RECEIVED = "push.received";
    public static final String PUSH_REJECTED = "push.rejected";

    // Auth filter
    public static final String AUTH_SKIPPED       = "auth.skipped";
    public static final String AUTH_VERIFY_START  = "auth.verify.start";
    public static final String AUTH_VERIFY_DONE   = "auth.verify.done";
    public static final String AUTH_VERIFY_FAILED = "auth.verify.failed";

    // Phase 3: cross-BPP offer resolution
    public static final String OFFER_RESOLVE_COMPLETED = "offer.resolve.completed";
    public static final String OFFER_RESOLVE_SKIPPED   = "offer.resolve.skipped";

    // FULL replace
    public static final String FULL_REPLACE_DELETED = "full.replace.deleted";
}
