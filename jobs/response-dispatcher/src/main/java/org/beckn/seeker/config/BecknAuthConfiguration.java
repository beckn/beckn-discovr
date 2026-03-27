package org.beckn.seeker.config;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.BecknAuthConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BecknAuthConfiguration {

    @Value("${signing.enabled:false}")
    private boolean signingEnabled;

    @Value("${signing.subscriber-id:}")
    private String subscriberId;

    @Value("${signing.key-id-suffix:}")
    private String keyIdSuffix;

    @Value("${signing.private-key:}")
    private String privateKey;

    @Value("${signing.expiry-seconds:3600}")
    private long expirySeconds;

    @Bean(destroyMethod = "shutdown")
    public BecknAuth becknAuth() {
        return new BecknAuth(BecknAuthConfig.builder()
                .signingEnabled(signingEnabled)
                .subscriberId(subscriberId)
                .keyIdSuffix(keyIdSuffix)
                .privateKey(privateKey)
                .expirySeconds(expirySeconds)
                .build());
    }
}
