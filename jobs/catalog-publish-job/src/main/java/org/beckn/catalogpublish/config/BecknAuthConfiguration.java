package org.beckn.catalogpublish.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.BecknAuthConfig;
import org.beckn.catalogpublish.auth.BecknAuthFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    @Bean
    public FilterRegistrationBean<BecknAuthFilter> becknAuthFilterRegistration(
            BecknAuth becknAuth, AuthProperties props, ObjectMapper objectMapper) {
        FilterRegistrationBean<BecknAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BecknAuthFilter(becknAuth, props, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
