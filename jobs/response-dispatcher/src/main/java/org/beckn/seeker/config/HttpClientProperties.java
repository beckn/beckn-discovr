package org.beckn.seeker.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the HTTP client used by the response dispatcher.
 * Bound from {@code http.client.*} in application.yml.
 */
@Validated
@ConfigurationProperties(prefix = "http.client")
public record HttpClientProperties(
        @Positive int timeout,
        @Positive int connectionTimeout,
        boolean urlValidationEnabled
) {}
