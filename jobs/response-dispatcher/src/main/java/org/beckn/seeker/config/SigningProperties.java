package org.beckn.seeker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "signing")
public record SigningProperties(
        boolean enabled,
        String subscriberId,
        String keyIdSuffix,
        String privateKey,
        long expirySeconds) {

    public SigningProperties {
        if (subscriberId == null) subscriberId = "";
        if (keyIdSuffix == null) keyIdSuffix = "";
        if (privateKey == null) privateKey = "";
        if (expirySeconds <= 0) expirySeconds = 3600;
    }
}
