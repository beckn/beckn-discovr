package org.beckn.crawler.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * All crawler settings — bound from {@code crawler.*} in application.yml / env.
 * Nothing is hardcoded: providers, endpoint, cadence, timeouts and paths all come from config.
 */
@Validated
@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
        /**
         * Full DeDi manifest URLs to crawl (each a complete URL to a manifest / dedi.json).
         * Used when {@code source=config}; may be empty when {@code source=db}.
         */
        List<String> providers,
        /**
         * Where the manifest-URL list comes from: {@code config} (the {@code providers} list) or
         * {@code db} (active rows in the {@code crawler_source} table, re-read every index poll).
         */
        @NotNull String source,
        /** Absolute URL of the discover /catalog/push endpoint. */
        @NotNull String pushEndpoint,
        /**
         * How often to re-read the manifest. The manifest rarely changes (provider identity +
         * where the index lives + publisher key), so this is long — e.g. weekly. In the target
         * architecture the manifest lives in a DeDi service, separate from the bucket.
         */
        @NotNull Duration manifestRefreshInterval,
        /**
         * How often to poll each provider's index for catalog changes. Short — e.g. per minute.
         * The index (and catalog parts) live in the cloud bucket and change on every publish.
         */
        @NotNull Duration indexPollInterval,
        @NotNull Http http,
        /** Path to the append-only feedback log file. */
        @NotNull String feedbackLogPath) {

    public record Http(
            @NotNull Duration timeout,
            /** Safety cap on a single fetched part (bytes). */
            long maxPartBytes,
            /**
             * Append a unique {@code ?cb=} query param to every GET so CDN-cached buckets
             * (e.g. GitHub raw, max-age 300s) always return fresh bytes. Digests are computed
             * over the body, not the URL, so this is safe. Default on for the GitHub-raw POC;
             * turn off for a real object store that serves fresh or honours ETags.
             */
            boolean cacheBust) {}
}
