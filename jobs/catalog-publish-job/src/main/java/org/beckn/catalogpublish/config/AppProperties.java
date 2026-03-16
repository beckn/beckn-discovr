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
                @Valid Catalog catalog,
                @Valid Http http) {
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
                        @NotBlank String schemaFile,
                        @Min(1) int parallelCatalogThreshold,
                        @Min(1) int processingPoolSize,
                        @Valid Elasticsearch elasticsearch,
                        @Valid TextSearch textSearch) {
        }

        public record Http(boolean signatureVerificationEnabled) {
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
