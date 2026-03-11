package org.beckn.catalogpublish.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Ensures an ES index and alias exist for a given schema type on-demand.
 * Index names are derived from the item's @type (e.g. "GroceryItem" →
 * "beckn-catalog-groceryitem"). A shared index template is created lazily
 * on the first index creation.
 */
@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class EsIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EsIndexManager.class);
    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9-]");
    private static final String TEMPLATE_NAME = "beckn-catalog-template";

    private final ElasticsearchClient esClient;
    private final EsIndexerMetrics    metrics;
    private final String              indexPrefix;
    private final String              aliasName;
    private final AtomicBoolean       templateCreated = new AtomicBoolean(false);

    public EsIndexManager(ElasticsearchClient esClient,
                          EsIndexerMetrics metrics,
                          AppProperties props) {
        this.esClient    = esClient;
        this.metrics     = metrics;
        this.indexPrefix = props.catalog().elasticsearch().indexPrefix();
        this.aliasName   = props.catalog().elasticsearch().aliasName();
    }

    public String resolveIndexName(String schemaType) {
        return indexPrefix + "-" + UNSAFE.matcher(schemaType.toLowerCase()).replaceAll("-");
    }

    public String aliasName() {
        return aliasName;
    }

    /** Ensures the index (and alias) exist. Called on-demand before each bulk. */
    public void ensureIndex(String indexName) throws Exception {
        ensureTemplateOnce();
        if (esClient.indices().exists(r -> r.index(indexName)).value()) {
            ensureAlias(indexName);
            return;
        }
        esClient.indices().create(r -> r.index(indexName));
        ensureAlias(indexName);
        metrics.incrementIndexCreated();
        log.info("es.index.created name={} alias={}", indexName, aliasName);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void ensureTemplateOnce() {
        if (templateCreated.get()) return;
        try {
            esClient.indices().putIndexTemplate(t -> t.name(TEMPLATE_NAME).withJson(new StringReader(templateJson())));
            log.info("es.template.created name={}", TEMPLATE_NAME);
            templateCreated.set(true);
        } catch (Exception e) {
            log.warn("es.template.ensure.failed error={}", ErrorSanitizer.sanitize(e));
        }
    }

    private void ensureAlias(String indexName) throws Exception {
        if (!esClient.indices().existsAlias(r -> r.index(indexName).name(aliasName)).value())
            esClient.indices().putAlias(r -> r.index(indexName).name(aliasName));
    }

    private String templateJson() {
        return """
                {
                  "index_patterns": ["%s-*"],
                  "template": {
                    "settings": {
                      "index.mapping.total_fields.limit": 2000,
                      "index.mapping.depth.limit": 10
                    },
                    "mappings": {
                      "dynamic_templates": [
                        { "geo_fields":               { "path_match": "*.geo",                                                       "mapping": { "type": "geo_point" } } },
                        { "item_attrs_longs_as_float":   { "path_match": "item_attributes.*", "match_mapping_type": "long",   "mapping": { "type": "float" } } },
                        { "item_attrs_doubles_as_float": { "path_match": "item_attributes.*", "match_mapping_type": "double", "mapping": { "type": "float" } } },
                        { "strings_as_keywords":      { "match_mapping_type": "string",                                               "mapping": { "type": "keyword"   } } },
                        { "integers_as_ints":         { "match_mapping_type": "long",                                                 "mapping": { "type": "integer"   } } },
                        { "doubles_as_doubles":       { "match_mapping_type": "double",                                               "mapping": { "type": "double"    } } },
                        { "booleans":                 { "match_mapping_type": "boolean",                                              "mapping": { "type": "boolean"   } } }
                      ],
                      "properties": {
                        "catalog_id":        { "type": "keyword" },
                        "catalog_name":      { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "catalog_images":    { "type": "keyword" },
                        "item_id":           { "type": "keyword" },
                        "bpp_id":            { "type": "keyword" },
                        "bpp_uri":           { "type": "keyword" },
                        "network_id":        { "type": "keyword" },
                        "schema_type":       { "type": "keyword" },
                        "item_name":         { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "item_short_desc":   { "type": "text" },
                        "item_long_desc":    { "type": "text" },
                        "full_text_blob":    { "type": "text", "analyzer": "standard" },
                        "item_location":     { "type": "geo_point" },
                        "item_rating_value": { "type": "float" },
                        "item_is_active":    { "type": "boolean" },
                        "item_rateable":     { "type": "boolean" },
                        "item_attributes":   { "type": "object", "dynamic": true },
                        "offers":            { "type": "nested" },
                        "indexed_at":        { "type": "date" }
                      }
                    }
                  }
                }
                """
                .formatted(indexPrefix);
    }
}
