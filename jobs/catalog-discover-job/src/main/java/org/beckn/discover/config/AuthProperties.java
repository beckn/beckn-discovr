package org.beckn.discover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "discovery.auth")
public record AuthProperties(
        boolean enabled,
        String registryBaseUrl,
        String registryName,
        String registryToken,
        long clockSkewSeconds,
        long cacheTtlSeconds,
        int cacheMaxKeys,
        int timeoutSeconds,
        int retryAttempts,
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
