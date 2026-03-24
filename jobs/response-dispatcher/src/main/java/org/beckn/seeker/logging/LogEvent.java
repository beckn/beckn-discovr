package org.beckn.seeker.logging;

public final class LogEvent {
    private LogEvent() {}

    // Consumer lifecycle
    public static final String CONSUMER_RECEIVED  = "CONSUMER_RECEIVED";
    public static final String CONSUMER_PROCESSED = "CONSUMER_PROCESSED";
    public static final String CONSUMER_ERROR     = "CONSUMER_ERROR";

    // Callback delivery
    public static final String CALLBACK_RESOLVED         = "CALLBACK_RESOLVED";
    public static final String CALLBACK_SENT             = "CALLBACK_SENT";
    public static final String CALLBACK_ACK              = "CALLBACK_ACK";
    public static final String CALLBACK_NACK             = "CALLBACK_NACK";
    public static final String CALLBACK_ACK_NO_CALLBACK  = "CALLBACK_ACK_NO_CALLBACK";
    public static final String CALLBACK_ERROR            = "CALLBACK_ERROR";

    // Signature
    public static final String SIGNATURE_INIT      = "SIGNATURE_INIT";
    public static final String SIGNATURE_DISABLED  = "SIGNATURE_DISABLED";
    public static final String SIGNATURE_GENERATED = "SIGNATURE_GENERATED";
    public static final String SIGNATURE_FAILED    = "SIGNATURE_FAILED";

    // Dead-letter topic
    public static final String DLT_SENT   = "DLT_SENT";
    public static final String DLT_FAILED = "DLT_FAILED";
}
