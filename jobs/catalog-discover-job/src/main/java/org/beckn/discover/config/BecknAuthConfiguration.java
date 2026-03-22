package org.beckn.discover.config;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.BecknAuthConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class BecknAuthConfiguration {

    @Bean(destroyMethod = "shutdown")
    public BecknAuth becknAuth(AuthProperties props) {
        return new BecknAuth(BecknAuthConfig.builder()
                .verificationEnabled(props.enabled())
                .registryBaseUrl(props.registryBaseUrl())
                .registryName(props.registryName())
                .registryToken(props.registryToken())
                .allowedClockSkewSeconds(props.clockSkewSeconds())
                .cacheTtlSeconds(props.cacheTtlSeconds())
                .cacheMaxKeys(props.cacheMaxKeys())
                .timeoutSeconds(props.timeoutSeconds())
                .retryAttempts(props.retryAttempts())
                .build());
    }
}
