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

    // HTTP pull callback endpoint
    public static final String ON_PULL_RECEIVED = "on_pull.received";
    public static final String ON_PULL_REJECTED = "on_pull.rejected";
    public static final String ON_PULL_FAILED   = "on_pull.failed";
    public static final String ON_PULL_SSRF_DISABLED = "on_pull.ssrf.disabled";
    public static final String ON_PULL_MODE_SELECTED     = "on_pull.mode_selected";
    public static final String ON_PULL_DOWNLOAD_STARTED  = "on_pull.download.started";
    public static final String ON_PULL_CHECKSUM_VERIFIED = "on_pull.checksum.verified";
    public static final String ON_PULL_DECOMPRESSED      = "on_pull.decompressed";
    public static final String ON_PULL_CATALOG_ENQUEUED  = "on_pull.catalog.enqueued";
    public static final String ON_PULL_CATALOG_REJECTED  = "on_pull.catalog.rejected";
    public static final String ON_PULL_COMPLETED         = "on_pull.completed";
    // on_pull download-manifest failure reasons (distinct from the generic processing error)
    public static final String ON_PULL_DOWNLOAD_HTTP_ERROR = "on_pull.download.http_error";
    public static final String ON_PULL_SSRF_REJECT          = "on_pull.ssrf.reject";
    public static final String ON_PULL_CHECKSUM_MISMATCH    = "on_pull.checksum.mismatch";
    public static final String ON_PULL_DECOMPRESS_ERROR     = "on_pull.decompress.error";
    // on_pull download/decompress exceeded the configured hard cap (gzip-bomb / OOM guard)
    public static final String ON_PULL_SIZE_EXCEEDED        = "on_pull.size.exceeded";

    // Auth filter
    public static final String AUTH_SKIPPED       = "auth.skipped";
    public static final String AUTH_VERIFY_START  = "auth.verify.start";
    public static final String AUTH_VERIFY_DONE   = "auth.verify.done";
    public static final String AUTH_VERIFY_FAILED = "auth.verify.failed";

    // Phase 3: cross-BPP offer resolution
    public static final String OFFER_RESOLVE_COMPLETED = "offer.resolve.completed";
    public static final String OFFER_RESOLVE_SKIPPED   = "offer.resolve.skipped";

    // FULL replace
    public static final String FULL_REPLACE_DELETED    = "full.replace.deleted";
    public static final String FULL_REPLACE_ES_DELETED = "full.replace.es.deleted";

    // Merge mode
    public static final String MERGE_COMPLETED = "merge.completed";

    // Embedding
    public static final String EMBEDDING_CLIENT_INIT     = "embedding.client.init";
    public static final String EMBEDDING_RETRY           = "embedding.retry";
    public static final String EMBEDDING_ATTEMPT_FAILED  = "embedding.attempt.failed";
    public static final String EMBEDDING_FAILED          = "embedding.failed";
    public static final String EMBEDDING_EMPTY           = "embedding.empty";
    public static final String EMBEDDING_SERIALIZE_FAILED = "embedding.serialize.failed";

    // ES index management
    public static final String ES_INDEX_CREATED         = "es.index.created";
    public static final String ES_TEMPLATE_CREATED      = "es.template.created";
    public static final String ES_TEMPLATE_ENSURE_FAILED = "es.template.ensure.failed";
    public static final String ES_TEMPLATE_LOADED       = "es.template.loaded";
    public static final String ES_TEMPLATE_LOAD_FAILED  = "es.template.load.failed";

    // ES executor
    public static final String ES_INDEX_REJECTED        = "es.index.rejected";

    // Auth filter init
    public static final String AUTH_INIT                = "auth.init";

    // Provider-level offers
    public static final String PROVIDER_OFFER_PERSISTED = "provider.offer.persisted";
    public static final String PROVIDER_OFFER_DELETED   = "provider.offer.deleted";
    public static final String PROVIDER_OFFER_SKIPPED   = "provider.offer.skipped";

    // Text blob assembly
    public static final String FULL_TEXT_BLOB_TRUNCATED = "full.text.blob.truncated";

    // Geometry extraction
    public static final String GEO_MAX_DEPTH_EXCEEDED   = "geo.max-depth-exceeded";
    public static final String GEO_GPS_OUT_OF_RANGE     = "geo.gps.out-of-range";
    public static final String GEO_GPS_PARSE_FAILED     = "geo.gps.parse-failed";
    public static final String GEO_POLYGON_PARSE_FAILED = "geo.polygon.parse-failed";
    public static final String GEO_GEOJSON_UNSUPPORTED_TYPE  = "geo.geojson.unsupported-type";
    public static final String GEO_GEOJSON_PARSE_FAILED      = "geo.geojson.parse-failed";
    public static final String GEO_GEOJSON_POINT_OUT_OF_RANGE = "geo.geojson.point.out-of-range";
    public static final String GEO_EXTRACT_FAILED       = "geo.extract.failed";
    public static final String GEO_EXTRACT_PARSE_FAILED = "geo.extract.parse-failed";
}
