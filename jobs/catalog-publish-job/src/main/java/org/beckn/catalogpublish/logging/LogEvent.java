package org.beckn.catalogpublish.logging;

public final class LogEvent {
    private LogEvent() {}

    // Consumer lifecycle
    public static final String CONSUMER_RECEIVED  = "CONSUMER_RECEIVED";
    public static final String CONSUMER_PROCESSED = "CONSUMER_PROCESSED";
    public static final String CONSUMER_ERROR     = "CONSUMER_ERROR";
    public static final String CONSUMER_REJECTED  = "CONSUMER_REJECTED";

    // Parse step
    public static final String PARSE_COMPLETED = "PARSE_COMPLETED";
    public static final String PARSE_FAILED    = "PARSE_FAILED";

    // Validate step
    public static final String VALIDATE_PASSED = "VALIDATE_PASSED";
    public static final String VALIDATE_FAILED = "VALIDATE_FAILED";

    // Persistence step
    public static final String PERSIST_COMPLETED = "PERSIST_COMPLETED";
    public static final String PERSIST_FAILED    = "PERSIST_FAILED";

    // Elasticsearch indexing
    public static final String ES_INDEXED = "ES_INDEXED";
    public static final String ES_FAILED  = "ES_FAILED";

    // Kafka publish
    public static final String KAFKA_SENT   = "KAFKA_SENT";
    public static final String KAFKA_FAILED = "KAFKA_FAILED";

    // HTTP push endpoint
    public static final String PUSH_RECEIVED = "PUSH_RECEIVED";
    public static final String PUSH_REJECTED = "PUSH_REJECTED";
}
