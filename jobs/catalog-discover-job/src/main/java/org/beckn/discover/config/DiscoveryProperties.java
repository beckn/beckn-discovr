package org.beckn.discover.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Discovery Service
 */
@Configuration
@ConfigurationProperties(prefix = "discovery")
@Validated
public class DiscoveryProperties {

    private boolean latencyTrackingEnabled = true;
    @Valid private Kafka kafka = new Kafka();
    @Valid private NLWeb nlweb = new NLWeb();
    @Valid private PostgreSQL postgresql = new PostgreSQL();
    @Valid private Schema schema = new Schema();
    @Valid private RegistryAuthConfig registryAuth = new RegistryAuthConfig();
    @Valid private TextSearch textSearch = new TextSearch();
    @Valid private Spatial spatial = new Spatial();
    @Valid private Elasticsearch elasticsearch = new Elasticsearch();

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

    public Spatial getSpatial() {
        return spatial;
    }

    public void setSpatial(Spatial spatial) {
        this.spatial = spatial;
    }

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(Elasticsearch elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    /**
     * Elasticsearch configuration — used when text-search.engine=elasticsearch.
     */
    public static class Elasticsearch {
        @NotBlank(message = "discovery.elasticsearch.hosts must not be blank")
        private String hosts = "http://localhost:9200";
        @NotBlank(message = "discovery.elasticsearch.alias-name must not be blank")
        private String aliasName = "beckn-catalog";
        private int resultLimit = 50;
        private float minScore = 0.72f;
        private int connectTimeoutMs = 5000;
        private int socketTimeoutMs = 30000;

        public String getHosts() { return hosts; }
        public void setHosts(String hosts) { this.hosts = hosts; }
        public String getAliasName() { return aliasName; }
        public void setAliasName(String aliasName) { this.aliasName = aliasName; }
        public int getResultLimit() { return resultLimit; }
        public void setResultLimit(int resultLimit) { this.resultLimit = resultLimit; }
        public float getMinScore() { return minScore; }
        public void setMinScore(float minScore) { this.minScore = minScore; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int socketTimeoutMs) { this.socketTimeoutMs = socketTimeoutMs; }
    }

    /**
     * Text search engine selection and AI model configuration.
     *
     * <p>Engine options:</p>
     * <ul>
     *   <li>{@code native-els}      — keyword BM25 multi_match (no AI, default)</li>
     *   <li>{@code els-semantic-search} — vector knn + optional LLM query enrichment</li>
     *   <li>{@code nlweb}           — NLWeb-based search</li>
     * </ul>
     */
    public static class TextSearch {
        private String engine = "native-els";
        private EmbeddingModel embeddingModel = new EmbeddingModel();
        private LlmModel llmModel = new LlmModel();

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public EmbeddingModel getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(EmbeddingModel embeddingModel) { this.embeddingModel = embeddingModel; }
        public LlmModel getLlmModel() { return llmModel; }
        public void setLlmModel(LlmModel llmModel) { this.llmModel = llmModel; }

        /**
         * Embedding model — converts text to vectors for semantic similarity search.
         * Supports any OpenAI-compatible /v1/embeddings provider (Ollama, OpenAI, Azure, etc.).
         * MUST match the model configured in catalog-publish-job.
         * Changing the model requires recreating the Elasticsearch index.
         */
        public static class EmbeddingModel {
            private String name = "nomic-embed-text";
            private String baseUrl = "http://localhost:11434";
            private String apiKey = "";
            private int timeoutMs = 10000;
            private int knnCandidates = 500;
            private int retries = 3;
            private long retryDelayMs = 1000;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public int getTimeoutMs() { return timeoutMs; }
            public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
            public int getKnnCandidates() { return knnCandidates; }
            public void setKnnCandidates(int knnCandidates) { this.knnCandidates = knnCandidates; }
            public int getRetries() { return retries; }
            public void setRetries(int retries) { this.retries = retries; }
            public long getRetryDelayMs() { return retryDelayMs; }
            public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
        }

        /**
         * LLM model — enriches the query with synonyms and domain vocabulary before embedding.
         * Optional when engine=els-semantic-search. When disabled, the raw query is embedded directly.
         * Supports any OpenAI-compatible /v1/chat/completions provider.
         */
        public static class LlmModel {
            private boolean enabled = true;
            private String name = "gpt-4o-mini";
            private String baseUrl = "https://api.openai.com";
            private String apiKey = "";
            private int timeoutMs = 30000;
            private int retries = 3;
            private long retryDelayMs = 1000;
            private double temperature = 0.0;
            private String systemPrompt =
                    "You are a search query enricher for a product and service catalog. " +
                    "Append up to 5 highly relevant synonyms or specifications to the original query. " +
                    "Keep the original query words first, then add only closely related terms. " +
                    "Return only the expanded query as a single short line. " +
                    "Do not add loosely related terms, brands, or categories. No explanation. No formatting.";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public int getTimeoutMs() { return timeoutMs; }
            public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
            public int getRetries() { return retries; }
            public void setRetries(int retries) { this.retries = retries; }
            public long getRetryDelayMs() { return retryDelayMs; }
            public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
            public double getTemperature() { return temperature; }
            public void setTemperature(double temperature) { this.temperature = temperature; }
            public String getSystemPrompt() { return systemPrompt; }
            public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        }
    }

    public static class Spatial {
        private String engine = "postgresql";

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
    }

    public static class Kafka {
        @NotBlank(message = "discovery.kafka.request-topic must not be blank")
        private String requestTopic;
        @NotBlank(message = "discovery.kafka.response-topic must not be blank")
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
        @NotBlank(message = "discovery.nlweb.base-url must not be blank")
        private String baseUrl;
        @NotBlank(message = "discovery.nlweb.ask-endpoint must not be blank")
        private String askEndpoint;
        private int timeoutSeconds;
        private boolean streaming;
        private int scoreThreshold = 80; // Default score threshold
        private int maxRetries;
        private long retryDelayMs;

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

    public static class PostgreSQL {
        private String host = "localhost";
        private int port = 5432;
        private String database = "catalog_db";
        private String username = "catalog_user";
        private String password = "";  // provide via POSTGRES_PASSWORD env var — no default
        private int resultLimit = 100;
        private boolean logExplainAnalyze = false;
        private int parallelQueryTimeoutSeconds = 10;
        /** Size of the dedicated I/O thread pool used for parallel queries (Path A fallback).
         *  Defaults to min(availableProcessors * 2, 8) when set to 0. */
        private int parallelQueryWorkers = 4;

        /**
         * List of catalog-level attributes to extract from catalog.payload
         * These attributes will be merged into the catalog object in the response
         * Default: ["offers"] - extracts offers from catalog payload
         */
        private java.util.List<String> catalogAttributesToExtract = java.util.List.of("offers");

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
        private String url = "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/draft/api/v2.0.0/beckn.yaml";
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
