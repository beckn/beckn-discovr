package org.beckn.discover.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Activates Elasticsearch text search beans when engine is either
 * {@code native-els} (keyword BM25) or {@code els-semantic-search} (vector knn).
 */
public class EsTextSearchCondition extends AnyNestedCondition {

    public EsTextSearchCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "native-els")
    static class NativeEls {}

    @ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "els-semantic-search")
    static class SemanticSearch {}
}
