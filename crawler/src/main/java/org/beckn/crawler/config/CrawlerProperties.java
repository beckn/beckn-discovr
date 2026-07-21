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
        /** Provider base URLs. POC: domains only; DeDi registry replaces this later. */
        @NotEmpty List<String> providers,
        /** Fixed DeDi standard path appended to each provider base to reach the manifest. */
        @NotNull String wellKnownPath,
        /** Absolute URL of the discover /catalog/push endpoint. */
        @NotNull String pushEndpoint,
        /** How often a crawl pass runs (drives the "modified after N minutes" scenario). */
        @NotNull Duration pollInterval,
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
