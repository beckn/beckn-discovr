package org.beckn.catalogpublish.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Single source of truth for application configuration.
 * Replaces all @Value annotations across the job.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
                @Valid Datasource datasource,
                @Valid Messaging messaging,
                @Valid Catalog catalog) {
        public record Datasource(
                        @NotBlank String url,
                        @NotBlank String driverClassName,
                        @NotBlank String username,
                        String password,
                        @Valid Hikari hikari) {
        }

        public record Hikari(
                        int maximumPoolSize,
                        int minimumIdle,
                        long connectionTimeout,
                        long idleTimeout,
                        long maxLifetime) {
        }

        public record Messaging(
                        @NotBlank String brokerServers,
                        @Valid Consumer consumer,
                        @Valid Topics topics) {
        }

        public record Consumer(
                        @NotBlank String groupId,
                        int concurrency,
                        int maxPollRecords,
                        int sessionTimeoutMs,
                        int maxPollIntervalMs) {
        }

        public record Topics(
                        @NotBlank String ingestionRequests,
                        @NotBlank String catalogEvents,
                        @NotBlank String responses,
                        @NotBlank String failed) {
        }

        public record Catalog(
                        @Positive long maxPayloadSize,
                        boolean validationEnabled,
                        @NotBlank String schemaUrl,
                        @Min(1) int parallelCatalogThreshold,
                        @Min(1) int processingPoolSize,
                        @Valid Elasticsearch elasticsearch,
                        @Valid TextSearch textSearch,
                        @Valid Indexing indexing,
                        Boolean pullSsrfCheckEnabled,
                        Long pullMaxDownloadBytes,
                        Long pullMaxDecompressedBytes,
                        Integer pullDownloadMaxAttempts,
                        Long pullDownloadRetryBackoffMs) {
                /** Default hard cap for the compressed bytes downloaded on the on_pull path: 50 MiB. */
                public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 52_428_800L;
                /** Default hard cap for the decompressed (gunzipped) bytes on the on_pull path: 200 MiB. */
                public static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 209_715_200L;
                /** Default total attempts (1 initial + 2 retries) for a transient on_pull download failure. */
                public static final int DEFAULT_DOWNLOAD_MAX_ATTEMPTS = 3;
                /** Default base backoff between on_pull download retries (multiplied by attempt number). */
                public static final long DEFAULT_DOWNLOAD_RETRY_BACKOFF_MS = 1_000L;

                /**
                 * Secure-by-default: when {@code app.catalog.pull-ssrf-check-enabled} is absent
                 * (binds to {@code null}), the SSRF guard on the on_pull download path stays ENABLED.
                 * Only an explicit {@code false} disables it (for local/dev). A {@code Boolean}
                 * component is used deliberately so the absent case defaults to {@code true} rather
                 * than a primitive {@code boolean}'s insecure {@code false}.
                 *
                 * <p>{@code maxDownloadBytes} / {@code maxDecompressedBytes} follow the same
                 * secure-default pattern: a {@code Long} so an absent (null) value defaults to the
                 * bounded constant above rather than an unbounded download/decompress. They cap the
                 * on_pull download path to defend against gzip-bomb / OOM (env-bindable via
                 * {@code APP_CATALOG_PULL_MAX_DOWNLOAD_BYTES} / {@code APP_CATALOG_PULL_MAX_DECOMPRESSED_BYTES}).</p>
                 */
                public Catalog {
                        if (pullSsrfCheckEnabled == null) {
                                pullSsrfCheckEnabled = Boolean.TRUE;
                        }
                        if (pullMaxDownloadBytes == null) {
                                pullMaxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES;
                        }
                        if (pullMaxDecompressedBytes == null) {
                                pullMaxDecompressedBytes = DEFAULT_MAX_DECOMPRESSED_BYTES;
                        }
                        if (pullDownloadMaxAttempts == null || pullDownloadMaxAttempts < 1) {
                                pullDownloadMaxAttempts = DEFAULT_DOWNLOAD_MAX_ATTEMPTS;
                        }
                        if (pullDownloadRetryBackoffMs == null || pullDownloadRetryBackoffMs < 0) {
                                pullDownloadRetryBackoffMs = DEFAULT_DOWNLOAD_RETRY_BACKOFF_MS;
                        }
                }
        }

        /**
         * Text search indexing configuration.
         * Controls whether item vectors are generated and stored in Elasticsearch
         * for semantic search support in the discovery service.
         */
        public record TextSearch(@Valid EmbeddingModel embeddingModel) {}

        /**
         * Embedding model for converting item text to vectors at index time.
         * Supports any OpenAI-compatible /v1/embeddings provider.
         * IMPORTANT: model name MUST match discovery-job text-search.embedding-model.name.
         * Changing the model requires recreating the Elasticsearch index.
         */
        public record EmbeddingModel(
                        boolean enabled,
                        String name,
                        String baseUrl,
                        String apiKey,
                        int timeoutMs,
                        int retries,
                        long retryDelayMs) {}

        public record Indexing(
                        @Min(1) int maxTextBlobBytes) {
        }

        public record Elasticsearch(
                        boolean enabled,
                        String hosts,
                        String indexPrefix,
                        String aliasName,
                        String failureTopic,
                        String finalDlqTopic,
                        int bulkBatchSize,
                        int retryAttempts,
                        long retryInitialDelayMs,
                        int poolSize,
                        int poolQueueCapacity,
                        int maxFailureAttempts,
                        @Valid Mapping mapping) {

                /** Configurable ES index template mapping settings. */
                public record Mapping(
                                int totalFieldsLimit,
                                int depthLimit,
                                String templateFile) {
                }
        }
}
