package org.beckn.discover.config;

import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

public class BecknPrefixHandlerMapping extends RequestMappingHandlerMapping {

    private final String prefix;

    public BecknPrefixHandlerMapping(String prefix) {
        this.prefix = prefix;
    }

    @Override
    protected boolean isHandler(Class<?> beanType) {
        // Only prefix application controllers — exclude Spring Boot's own controllers
        // (e.g. BasicErrorController at /error) which must stay at their standard paths.
        return super.isHandler(beanType)
                && !beanType.getPackageName().startsWith("org.springframework.");
    }

    @Override
    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null) return null;
        RequestMappingInfo prefixInfo = RequestMappingInfo
                .paths(prefix)
                .options(getBuilderConfiguration())
                .build();
        return prefixInfo.combine(info);
    }
}
