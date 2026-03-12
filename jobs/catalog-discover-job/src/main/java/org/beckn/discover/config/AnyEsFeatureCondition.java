package org.beckn.discover.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Spring condition that activates Elasticsearch beans when EITHER the text-search engine
 * OR the spatial engine is set to "elasticsearch".
 *
 * Used on EsSearchConfig to ensure the ElasticsearchClient bean is created whenever
 * any ES feature is enabled.
 */
public class AnyEsFeatureCondition extends AnyNestedCondition {

    public AnyEsFeatureCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "elasticsearch")
    static class TextSearchIsEs {}

    @ConditionalOnProperty(name = "discovery.spatial.engine", havingValue = "elasticsearch")
    static class SpatialIsEs {}
}
