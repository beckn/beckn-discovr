package org.beckn.seeker.config;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.BecknAuthConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SigningProperties.class)
public class BecknAuthConfiguration {

    @Bean(destroyMethod = "shutdown")
    public BecknAuth becknAuth(SigningProperties props) {
        return new BecknAuth(BecknAuthConfig.builder()
                .signingEnabled(props.enabled())
                .subscriberId(props.subscriberId())
                .keyIdSuffix(props.keyIdSuffix())
                .privateKey(props.privateKey())
                .expirySeconds(props.expirySeconds())
                .build());
    }
}
