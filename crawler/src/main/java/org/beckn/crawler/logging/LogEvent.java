package org.beckn.crawler.logging;

/**
 * Log event names (the log message itself), mirroring the discover/publish jobs' convention where
 * the message is a stable dotted event id and the data rides along as value("key",val) args.
 */
public final class LogEvent {

    private LogEvent() {}

    // startup
    public static final String STARTUP_CONFIG      = "crawler.startup.config";

    // pass lifecycle
    public static final String PASS_STARTED        = "crawler.pass.started";
    public static final String PASS_COMPLETED      = "crawler.pass.completed";
    public static final String PASS_FAILED         = "crawler.pass.failed";

    // per-provider
    public static final String PROVIDER_CHECKING   = "crawler.provider.checking";
    public static final String PROVIDER_FAILED     = "crawler.provider.failed";
    public static final String PROVIDER_DONE       = "crawler.provider.done";
    public static final String PROVIDER_RETRY      = "crawler.provider.retry";

    // manifest / index
    public static final String MANIFEST_RESOLVED   = "crawler.manifest.resolved";
    public static final String INDEX_UNCHANGED     = "crawler.index.unchanged";
    public static final String INDEX_CHANGED       = "crawler.index.changed";
    public static final String INDEX_VERIFIED      = "crawler.index.verified";
    public static final String INDEX_INTEGRITY_FAILED = "crawler.index.integrity.failed";

    // per-catalog decisions
    public static final String CATALOG_UNCHANGED   = "crawler.catalog.unchanged";
    public static final String CATALOG_NONPUBLIC   = "crawler.catalog.skipped.nonpublic";
    public static final String CATALOG_ROLLBACK    = "crawler.catalog.skipped.rollback";
    public static final String CATALOG_RETIRED     = "crawler.catalog.retired";
    public static final String CATALOG_CHANGED     = "crawler.catalog.changed";
    public static final String PART_FETCHED        = "crawler.part.fetched";
    public static final String CATALOG_PUSHED      = "crawler.catalog.pushed";
    public static final String CATALOG_PUSH_REJECTED = "crawler.catalog.push.rejected";
    public static final String CATALOG_DIGEST_MISMATCH = "crawler.catalog.digest.mismatch";
    public static final String CATALOG_FETCH_FAILED = "crawler.catalog.fetch.failed";

    // feedback
    public static final String FEEDBACK            = "crawler.feedback";
    public static final String FEEDBACK_WRITE_FAILED = "crawler.feedback.write.failed";
}
