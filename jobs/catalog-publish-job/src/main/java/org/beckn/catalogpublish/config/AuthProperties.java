package org.beckn.catalogpublish.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        boolean enabled,
        String registryBaseUrl,
        String registryName,
        String registryToken,
        @Positive long clockSkewSeconds,
        @Positive long cacheTtlSeconds,
        @Positive int cacheMaxKeys,
        @Positive int timeoutSeconds,
        @Positive int retryAttempts,
        List<String> whitelistedEndpoints) {

    public AuthProperties {
        if (registryBaseUrl == null) registryBaseUrl = "";
        if (registryName == null) registryName = "keys";
        if (registryToken == null) registryToken = "";
        if (clockSkewSeconds <= 0) clockSkewSeconds = 30;
        if (cacheTtlSeconds <= 0) cacheTtlSeconds = 2592000;
        if (cacheMaxKeys <= 0) cacheMaxKeys = 100;
        if (timeoutSeconds <= 0) timeoutSeconds = 10;
        if (retryAttempts <= 0) retryAttempts = 3;
        if (whitelistedEndpoints == null) whitelistedEndpoints = List.of();
    }
}
