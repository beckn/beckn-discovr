package org.beckn.catalogpublish.config;

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
