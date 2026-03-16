package org.beckn.discover.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Spring condition that activates the Elasticsearch client bean when ANY ES feature is needed:
 * <ul>
 *   <li>{@code discovery.text-search.engine=native-els}        — keyword BM25 text search</li>
 *   <li>{@code discovery.text-search.engine=els-semantic-search}   — vector knn + intent parsing</li>
 *   <li>{@code discovery.spatial.engine=elasticsearch}         — geo spatial search</li>
 * </ul>
 */
public class AnyEsFeatureCondition extends AnyNestedCondition {

    public AnyEsFeatureCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "native-els")
    static class TextSearchIsNativeEs {}

    @ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "els-semantic-search")
    static class TextSearchIsSemantic {}

    @ConditionalOnProperty(name = "discovery.spatial.engine", havingValue = "elasticsearch")
    static class SpatialIsEs {}
}
