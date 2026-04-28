package org.beckn.seeker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "registry")
public record RegistryProperties(
        String baseUrl,
        String name) {

    public RegistryProperties {
        if (baseUrl == null) baseUrl = "";
        if (name == null || name.isBlank()) name = "keys";
    }
}
