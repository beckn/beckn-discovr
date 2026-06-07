package org.beckn.seeker.logging;

public final class LogEvent {
    private LogEvent() {}

    // Consumer lifecycle
    public static final String CONSUMER_RECEIVED              = "consumer.received";
    public static final String CONSUMER_PROCESSED             = "consumer.processed";
    public static final String CONSUMER_ERROR                 = "consumer.error";
    public static final String CONSUMER_ENVELOPE_PARSE_FAILED = "consumer.envelope.parse.failed";

    // Callback delivery
    public static final String CALLBACK_RESOLVED         = "callback.resolved";
    public static final String CALLBACK_SENT             = "callback.sent";
    public static final String CALLBACK_ACK              = "callback.ack";
    public static final String CALLBACK_NACK             = "callback.nack";
    public static final String CALLBACK_ACK_NO_CALLBACK  = "callback.ack.no.callback";
    public static final String CALLBACK_ERROR            = "callback.error";

    // Signature
    public static final String SIGNATURE_INIT      = "signature.init";
    public static final String SIGNATURE_DISABLED  = "signature.disabled";
    public static final String SIGNATURE_GENERATED = "signature.generated";
    public static final String SIGNATURE_FAILED    = "signature.failed";

    // Registry resolution
    public static final String REGISTRY_RESOLVED = "registry.resolved";
    public static final String REGISTRY_FAILED   = "registry.failed";
    public static final String REGISTRY_FALLBACK = "registry.fallback";
    public static final String SSRF_BLOCKED      = "ssrf.blocked";

    // Dead-letter topic
    public static final String DLT_SENT   = "dlt.sent";
    public static final String DLT_FAILED = "dlt.failed";
}
