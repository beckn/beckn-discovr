package org.beckn.seeker.config;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.BecknAuthConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SigningProperties.class, RegistryProperties.class})
public class BecknAuthConfiguration {

    @Bean(destroyMethod = "shutdown")
    public BecknAuth becknAuth(SigningProperties signing, RegistryProperties registry) {
        var builder = BecknAuthConfig.builder()
                .signingEnabled(signing.enabled())
                .subscriberId(signing.subscriberId())
                .keyIdSuffix(signing.keyIdSuffix())
                .privateKey(signing.privateKey())
                .expirySeconds(signing.expirySeconds());

        // Enable registry lookup when registry URL is configured
        if (registry.baseUrl() != null && !registry.baseUrl().isBlank()) {
            builder.verificationEnabled(true)
                    .registryBaseUrl(registry.baseUrl())
                    .registryName(registry.name());
        }

        return new BecknAuth(builder.build());
    }
}
