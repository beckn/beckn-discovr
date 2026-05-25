package org.beckn.discover.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;

@Configuration
public class BecknPrefixConfig implements WebMvcRegistrations {

    private final String apiPrefix;

    public BecknPrefixConfig(@Value("${beckn.api-prefix:/beckn}") String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new BecknPrefixHandlerMapping(apiPrefix);
    }
}
