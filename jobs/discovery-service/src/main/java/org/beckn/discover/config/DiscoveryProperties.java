package org.beckn.discover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Discovery Service
 */
@Configuration
@ConfigurationProperties(prefix = "discovery")
public class DiscoveryProperties {

    private boolean latencyTrackingEnabled = true;
    private Kafka kafka = new Kafka();
    private NLWeb nlweb = new NLWeb();
    private PostgreSQL postgresql = new PostgreSQL();
    private Schema schema = new Schema();
    private RegistryAuthConfig registryAuth = new RegistryAuthConfig();
    private TextSearch textSearch = new TextSearch();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public NLWeb getNlweb() {
        return nlweb;
    }

    public void setNlweb(NLWeb nlweb) {
        this.nlweb = nlweb;
    }

    public PostgreSQL getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(PostgreSQL postgresql) {
        this.postgresql = postgresql;
    }

    public Schema getSchema() {
        return schema;
    }

    public void setSchema(Schema schema) {
        this.schema = schema;
    }

    public boolean isLatencyTrackingEnabled() {
        return latencyTrackingEnabled;
    }

    public void setLatencyTrackingEnabled(boolean latencyTrackingEnabled) {
        this.latencyTrackingEnabled = latencyTrackingEnabled;
    }

    public RegistryAuthConfig getRegistryAuth() {
        return registryAuth;
    }

    public void setRegistryAuth(RegistryAuthConfig registryAuth) {
        this.registryAuth = registryAuth;
    }

    public TextSearch getTextSearch() {
        return textSearch;
    }

    public void setTextSearch(TextSearch textSearch) {
        this.textSearch = textSearch;
    }

    /**
     * Text search engine selection.
     * Set {@code discovery.text-search.engine} to {@code nlweb} (default) or
     * {@code elasticsearch} to switch backends with no code changes.
     */
    public static class TextSearch {
        /** Active text search backend: "nlweb" (default) or "elasticsearch". */
        private String engine = "nlweb";

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
    }

    public static class Kafka {
        private String requestTopic;
        private String responseTopic;

        public String getRequestTopic() {
            return requestTopic;
        }

        public void setRequestTopic(String requestTopic) {
            this.requestTopic = requestTopic;
        }

        public String getResponseTopic() {
            return responseTopic;
        }

        public void setResponseTopic(String responseTopic) {
            this.responseTopic = responseTopic;
        }
    }

    public static class NLWeb {
        private String baseUrl;
        private String askEndpoint;
        private int timeoutSeconds;
        private boolean streaming;
        private int scoreThreshold = 80; // Default score threshold

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAskEndpoint() {
            return askEndpoint;
        }

        public void setAskEndpoint(String askEndpoint) {
            this.askEndpoint = askEndpoint;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public int getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(int scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }
    }

    public static class PostgreSQL {
        private String host = "localhost";
        private int port = 5432;
        private String database = "catalog_db";
        private String username = "catalog_user";
        private String password = "";  // provide via POSTGRES_PASSWORD env var — no default
        private int maxPoolSize = 10;
        private int resultLimit = 100;
        private boolean logExplainAnalyze = false;
        private int parallelQueryTimeoutSeconds = 10;
        /** Size of the dedicated I/O thread pool used for parallel queries (Path A fallback).
         *  Defaults to min(availableProcessors * 2, 8) when set to 0. */
        private int parallelQueryWorkers = 4;

        /**
         * List of catalog-level attributes to extract from catalog.payload
         * These attributes will be merged into the catalog object in the response
         * Default: ["beckn:offers"] - extracts offers from catalog payload
         */
        private java.util.List<String> catalogAttributesToExtract = java.util.List.of("beckn:offers");

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public java.util.List<String> getCatalogAttributesToExtract() {
            return catalogAttributesToExtract;
        }

        public void setCatalogAttributesToExtract(java.util.List<String> catalogAttributesToExtract) {
            this.catalogAttributesToExtract = catalogAttributesToExtract;
        }

        public int getResultLimit() {
            return resultLimit;
        }

        public void setResultLimit(int resultLimit) {
            this.resultLimit = resultLimit;
        }

        public boolean isLogExplainAnalyze() {
            return logExplainAnalyze;
        }

        public void setLogExplainAnalyze(boolean logExplainAnalyze) {
            this.logExplainAnalyze = logExplainAnalyze;
        }

        public int getParallelQueryTimeoutSeconds() {
            return parallelQueryTimeoutSeconds;
        }

        public void setParallelQueryTimeoutSeconds(int parallelQueryTimeoutSeconds) {
            this.parallelQueryTimeoutSeconds = parallelQueryTimeoutSeconds;
        }

        public int getParallelQueryWorkers() {
            return parallelQueryWorkers;
        }

        public void setParallelQueryWorkers(int parallelQueryWorkers) {
            this.parallelQueryWorkers = parallelQueryWorkers;
        }
    }

    public static class Schema {
        private String url = "https://raw.githubusercontent.com/beckn/protocol-specifications-new/refs/heads/draft/api-specs/beckn-protocol-api.yaml";
        private long cacheTtlHours = 1;
        private int fetchTimeoutSeconds = 30;
        private int maxRetries = 3;
        private long retryDelayMs = 1000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public long getCacheTtlHours() {
            return cacheTtlHours;
        }

        public void setCacheTtlHours(long cacheTtlHours) {
            this.cacheTtlHours = cacheTtlHours;
        }

        public int getFetchTimeoutSeconds() {
            return fetchTimeoutSeconds;
        }

        public void setFetchTimeoutSeconds(int fetchTimeoutSeconds) {
            this.fetchTimeoutSeconds = fetchTimeoutSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        public void setRetryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
        }
    }

    /**
     * Registry Authorization Configuration
     */
    public static class RegistryAuthConfig {
        private boolean enabled = false;
        private String registryToken = "";
        private String baseUrl = "https://api.testnet.beckn.one/registry/dedi/lookup";
        private String registryName = "subscribers.beckn.one";
        private int cacheTtlSeconds = 2592000;
        private int cacheMaxKeys = 100;
        private boolean cacheEnabled = true;
        private int retryAttempts = 3;
        private int timeoutSeconds = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRegistryToken() {
            return registryToken;
        }

        public void setRegistryToken(String registryToken) {
            this.registryToken = registryToken;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRegistryName() {
            return registryName;
        }

        public void setRegistryName(String registryName) {
            this.registryName = registryName;
        }

        public int getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(int cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }

        public int getCacheMaxKeys() {
            return cacheMaxKeys;
        }

        public void setCacheMaxKeys(int cacheMaxKeys) {
            this.cacheMaxKeys = cacheMaxKeys;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
