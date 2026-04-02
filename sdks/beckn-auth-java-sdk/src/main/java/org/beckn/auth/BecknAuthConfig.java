package org.beckn.auth;

import org.beckn.auth.cache.Cache;
import org.beckn.auth.cache.CacheFactory;
import org.beckn.auth.logging.Logger;
import org.beckn.auth.logging.LoggerFactory;

/**
 * Immutable configuration for the Beckn Auth SDK.
 * <p>
 * Property names mirror the existing service configs exactly:
 * signing fields match {@code signing.*} from response-dispatcher,
 * and registry-auth fields match {@code discovery.registry-auth.*}
 * from discovery-service-v2.
 * </p>
 *
 * <h3>Capability Flags</h3>
 * <ul>
 * <li><b>Signing</b> — {@code signingEnabled(true)} requires
 * {@code subscriberId}, {@code keyIdSuffix}, and {@code privateKey}.</li>
 * <li><b>Verification</b> — {@code verificationEnabled(true)} requires
 * {@code registryBaseUrl} and {@code registryName}.</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * BecknAuthConfig config = BecknAuthConfig.builder()
 *     .signingEnabled(true)
 *     .subscriberId("example-bap.com")
 *     .keyIdSuffix("ae3ea24b-cfec-495e-81f8-044aaef164ac")
 *     .privateKey("Base64EncodedPrivateKey")
 *     .verificationEnabled(true)
 *     .registryBaseUrl("https://registry.becknprotocol.io/subscribers")
 *     .registryName("keys")
 *     .build();
 * }</pre>
 */
public final class BecknAuthConfig {

    // ─── Capability flags ────────────────────────────────────────────────────────
    private final boolean signingEnabled;
    private final boolean verificationEnabled;

    // ─── Defaults ────────────────────────────────────────────────────────────────
    private static final long DEFAULT_EXPIRY_SECONDS = 3600;
    private static final long DEFAULT_ALLOWED_CLOCK_SKEW_SECONDS = 30;
    private static final long DEFAULT_CACHE_TTL_SECONDS = 2592000;
    private static final int DEFAULT_CACHE_MAX_KEYS = 100;
    private static final boolean DEFAULT_CACHE_ENABLED = true;
    private static final long DEFAULT_CACHE_CLEANUP_INTERVAL_SECONDS = 3600;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_RETRY_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_INITIAL_DELAY_MS = 500;
    private static final int DEFAULT_RETRY_MAX_DELAY_MS = 5000;

    // ─── Signing (matches signing.* in response-dispatcher) ──────────────────────
    private final String subscriberId;
    private final String keyIdSuffix;
    private final String privateKey;
    private final long expirySeconds;

    // ─── Registry auth (matches discovery.registry-auth.* in discovery-service-v2)
    private final String registryBaseUrl;
    private final String registryName;
    private final String registryToken;
    private final long cacheTtlSeconds;
    private final int cacheMaxKeys;
    private final boolean cacheEnabled;
    private final int retryAttempts;
    private final int timeoutSeconds;

    // ─── Internal tuning (not exposed in service configs) ────────────────────────
    private final long allowedClockSkewSeconds;
    private final long cacheCleanupIntervalSeconds;
    private final int retryInitialDelayMs;
    private final int retryMaxDelayMs;

    // ─── Pluggable ───────────────────────────────────────────────────────────────
    private final Logger logger;
    private final Cache cache;

    private BecknAuthConfig(Builder builder) {
        this.signingEnabled = builder.signingEnabled;
        this.verificationEnabled = builder.verificationEnabled;

        this.subscriberId = builder.subscriberId;
        this.keyIdSuffix = builder.keyIdSuffix;
        this.privateKey = builder.privateKey;
        this.expirySeconds = builder.expirySeconds;

        this.registryBaseUrl = builder.registryBaseUrl;
        this.registryName = builder.registryName;
        this.registryToken = builder.registryToken;
        this.cacheTtlSeconds = builder.cacheTtlSeconds;
        this.cacheMaxKeys = builder.cacheMaxKeys;
        this.cacheEnabled = builder.cacheEnabled;
        this.retryAttempts = builder.retryAttempts;
        this.timeoutSeconds = builder.timeoutSeconds;

        this.allowedClockSkewSeconds = builder.allowedClockSkewSeconds;
        this.cacheCleanupIntervalSeconds = builder.cacheCleanupIntervalSeconds;
        this.retryInitialDelayMs = builder.retryInitialDelayMs;
        this.retryMaxDelayMs = builder.retryMaxDelayMs;

        // Auto-detect logger and cache (cannot be overridden via Builder)
        this.logger = LoggerFactory.createLogger(BecknAuth.class);
        this.cache = CacheFactory.createCache(cacheTtlSeconds, cacheMaxKeys, cacheCleanupIntervalSeconds);
    }

    /** @return a new {@link Builder} instance */
    public static Builder builder() {
        return new Builder();
    }

    /** @return {@code true} if signing was explicitly enabled via {@code signingEnabled(true)} */
    public boolean isSigningEnabled() { return signingEnabled; }

    /** @return {@code true} if verification was explicitly enabled via {@code verificationEnabled(true)} */
    public boolean isVerificationEnabled() { return verificationEnabled; }

    /** @return subscriber ID — matches {@code signing.subscriber-id} */
    public String getSubscriberId() { return subscriberId; }

    /** @return key ID suffix — matches {@code signing.key-id-suffix} */
    public String getKeyIdSuffix() { return keyIdSuffix; }

    /** @return private key (raw Base64 or PEM) — matches {@code signing.private-key} */
    public String getPrivateKey() { return privateKey; }

    /** @return signature expiry in seconds — matches {@code signing.expiry-seconds} */
    public long getExpirySeconds() { return expirySeconds; }

    /** @return registry base URL — matches {@code discovery.registry-auth.base-url} */
    public String getRegistryBaseUrl() { return registryBaseUrl; }

    /** @return registry endpoint name — matches {@code discovery.registry-auth.registry-name} */
    public String getRegistryName() { return registryName; }

    /** @return Bearer token for registry API — matches {@code discovery.registry-auth.registry-token} */
    public String getRegistryToken() { return registryToken; }

    /** @return public key cache TTL in seconds — matches {@code discovery.registry-auth.cache-ttl-seconds} */
    public long getCacheTtlSeconds() { return cacheTtlSeconds; }

    /** @return max cache entries — matches {@code discovery.registry-auth.cache-max-keys} */
    public int getCacheMaxKeys() { return cacheMaxKeys; }

    /** @return whether public key caching is enabled — matches {@code discovery.registry-auth.cache-enabled} */
    public boolean isCacheEnabled() { return cacheEnabled; }

    /** @return registry request timeout in seconds — matches {@code discovery.registry-auth.timeout-seconds} */
    public int getTimeoutSeconds() { return timeoutSeconds; }

    /** @return registry retry attempts — matches {@code discovery.registry-auth.retry-attempts} */
    public int getRetryAttempts() { return retryAttempts; }

    /** @return allowed clock skew tolerance in seconds (default: 30) */
    public long getAllowedClockSkewSeconds() { return allowedClockSkewSeconds; }

    /** @return cache cleanup interval in seconds (default: 3600) */
    public long getCacheCleanupIntervalSeconds() { return cacheCleanupIntervalSeconds; }

    /** @return initial retry delay in milliseconds (default: 500) */
    public int getRetryInitialDelayMs() { return retryInitialDelayMs; }

    /** @return maximum retry delay in milliseconds (default: 5000) */
    public int getRetryMaxDelayMs() { return retryMaxDelayMs; }

    /** @return the configured {@link Logger} implementation */
    public Logger getLogger() { return logger; }

    /** @return the configured or auto-detected {@link Cache} implementation */
    public Cache getCache() { return cache; }

    static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ─── Builder ─────────────────────────────────────────────────────────────────

    /** Fluent builder for {@link BecknAuthConfig}. */
    public static final class Builder {

        private boolean signingEnabled = false;
        private boolean verificationEnabled = false;

        private String subscriberId;
        private String keyIdSuffix;
        private String privateKey;
        private long expirySeconds = DEFAULT_EXPIRY_SECONDS;

        private String registryBaseUrl;
        private String registryName;
        private String registryToken;
        private long cacheTtlSeconds = DEFAULT_CACHE_TTL_SECONDS;
        private int cacheMaxKeys = DEFAULT_CACHE_MAX_KEYS;
        private boolean cacheEnabled = DEFAULT_CACHE_ENABLED;
        private int retryAttempts = DEFAULT_RETRY_ATTEMPTS;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        private long allowedClockSkewSeconds = DEFAULT_ALLOWED_CLOCK_SKEW_SECONDS;
        private long cacheCleanupIntervalSeconds = DEFAULT_CACHE_CLEANUP_INTERVAL_SECONDS;
        private int retryInitialDelayMs = DEFAULT_RETRY_INITIAL_DELAY_MS;
        private int retryMaxDelayMs = DEFAULT_RETRY_MAX_DELAY_MS;

        private Builder() {}

        /**
         * Enables or disables signing. Matches {@code signing.enabled} in response-dispatcher.
         * When {@code true}, {@code subscriberId}, {@code keyIdSuffix}, and {@code privateKey} are required.
         */
        public Builder signingEnabled(boolean signingEnabled) {
            this.signingEnabled = signingEnabled;
            return this;
        }

        /**
         * Enables or disables verification. Matches {@code discovery.registry-auth.enabled} in discovery-service-v2.
         * When {@code true}, {@code registryBaseUrl} and {@code registryName} are required.
         */
        public Builder verificationEnabled(boolean verificationEnabled) {
            this.verificationEnabled = verificationEnabled;
            return this;
        }

        /** Matches {@code signing.subscriber-id}. Required when signing is enabled. */
        public Builder subscriberId(String subscriberId) {
            this.subscriberId = subscriberId;
            return this;
        }

        /** Matches {@code signing.key-id-suffix}. Required when signing is enabled. */
        public Builder keyIdSuffix(String keyIdSuffix) {
            this.keyIdSuffix = keyIdSuffix;
            return this;
        }

        /** Matches {@code signing.private-key}. Required when signing is enabled. */
        public Builder privateKey(String privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        /** Matches {@code signing.expiry-seconds}. Default: {@code 3600}. */
        public Builder expirySeconds(long expirySeconds) {
            this.expirySeconds = expirySeconds;
            return this;
        }

        /** Matches {@code discovery.registry-auth.base-url}. Required when verification is enabled. */
        public Builder registryBaseUrl(String registryBaseUrl) {
            this.registryBaseUrl = registryBaseUrl;
            return this;
        }

        /** Matches {@code discovery.registry-auth.registry-name}. Required when verification is enabled. */
        public Builder registryName(String registryName) {
            this.registryName = registryName;
            return this;
        }

        /** Matches {@code discovery.registry-auth.registry-token}. Optional Bearer token. */
        public Builder registryToken(String registryToken) {
            this.registryToken = registryToken;
            return this;
        }

        /** Matches {@code discovery.registry-auth.cache-ttl-seconds}. Default: {@code 2592000} (30 days). */
        public Builder cacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
            return this;
        }

        /** Matches {@code discovery.registry-auth.cache-max-keys}. Default: {@code 100}. */
        public Builder cacheMaxKeys(int cacheMaxKeys) {
            this.cacheMaxKeys = cacheMaxKeys;
            return this;
        }

        /** Matches {@code discovery.registry-auth.cache-enabled}. Default: {@code true}. */
        public Builder cacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
            return this;
        }

        /** Matches {@code discovery.registry-auth.retry-attempts}. Default: {@code 3}. */
        public Builder retryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
            return this;
        }

        /** Matches {@code discovery.registry-auth.timeout-seconds}. Default: {@code 10}. */
        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /** Clock skew tolerance for timestamp validation. Default: {@code 30}. */
        public Builder allowedClockSkewSeconds(long allowedClockSkewSeconds) {
            this.allowedClockSkewSeconds = allowedClockSkewSeconds;
            return this;
        }

        /** Cache cleanup sweep interval in seconds. Default: {@code 3600}. */
        public Builder cacheCleanupIntervalSeconds(long cacheCleanupIntervalSeconds) {
            this.cacheCleanupIntervalSeconds = cacheCleanupIntervalSeconds;
            return this;
        }

        /** Initial retry backoff delay in milliseconds. Default: {@code 500}. */
        public Builder retryInitialDelayMs(int retryInitialDelayMs) {
            this.retryInitialDelayMs = retryInitialDelayMs;
            return this;
        }

        /** Maximum retry backoff delay in milliseconds. Default: {@code 5000}. */
        public Builder retryMaxDelayMs(int retryMaxDelayMs) {
            this.retryMaxDelayMs = retryMaxDelayMs;
            return this;
        }

        /**
         * Validates the configuration and builds an immutable {@link BecknAuthConfig}.
         *
         * @throws IllegalArgumentException if {@code signingEnabled=true} but
         *         {@code subscriberId}, {@code keyIdSuffix}, or {@code privateKey} is missing
         * @throws IllegalArgumentException if {@code verificationEnabled=true} but
         *         {@code registryBaseUrl} or {@code registryName} is missing
         */
        public BecknAuthConfig build() {
            if (signingEnabled) {
                if (!isNotBlank(subscriberId))
                    throw new IllegalArgumentException("subscriberId is required when signingEnabled=true");
                if (!isNotBlank(keyIdSuffix))
                    throw new IllegalArgumentException("keyIdSuffix is required when signingEnabled=true");
                if (!isNotBlank(privateKey))
                    throw new IllegalArgumentException("privateKey is required when signingEnabled=true");
            }
            if (verificationEnabled) {
                if (!isNotBlank(registryBaseUrl))
                    throw new IllegalArgumentException("registryBaseUrl is required when verificationEnabled=true");
                if (!isNotBlank(registryName))
                    throw new IllegalArgumentException("registryName is required when verificationEnabled=true");
            }
            return new BecknAuthConfig(this);
        }

        private static boolean isNotBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
