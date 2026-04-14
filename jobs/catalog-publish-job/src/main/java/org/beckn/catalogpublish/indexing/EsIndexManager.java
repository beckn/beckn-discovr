package org.beckn.catalogpublish.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Ensures an ES index and alias exist for a given schema type on-demand.
 * Index names are derived from the item's @type (e.g. "GroceryItem" →
 * "beckn-catalog-groceryitem"). A shared index template is created lazily
 * on the first index creation.
 *
 * <p>Mapping template is configurable:
 * <ul>
 *   <li>{@code app.catalog.elasticsearch.mapping.template-file} — path to a custom
 *       template JSON file. When set, the file is loaded instead of the built-in
 *       default. The placeholder {@code ${INDEX_PREFIX}} in the file is replaced
 *       with the configured index prefix.</li>
 *   <li>{@code app.catalog.elasticsearch.mapping.total-fields-limit} — max fields
 *       per index (default 2000).</li>
 *   <li>{@code app.catalog.elasticsearch.mapping.depth-limit} — max mapping depth
 *       (default 10).</li>
 * </ul>
 * All settings are overridable via environment variables for Docker / Kubernetes.
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
    private final int                 totalFieldsLimit;
    private final int                 depthLimit;
    private final String              templateFile;
    private final AtomicBoolean       templateCreated = new AtomicBoolean(false);

    public EsIndexManager(ElasticsearchClient esClient,
                          EsIndexerMetrics metrics,
                          AppProperties props) {
        this.esClient    = esClient;
        this.metrics     = metrics;
        this.indexPrefix = props.catalog().elasticsearch().indexPrefix();
        this.aliasName   = props.catalog().elasticsearch().aliasName();

        var mapping = props.catalog().elasticsearch().mapping();
        this.totalFieldsLimit = mapping != null && mapping.totalFieldsLimit() > 0
                ? mapping.totalFieldsLimit() : 2000;
        this.depthLimit = mapping != null && mapping.depthLimit() > 0
                ? mapping.depthLimit() : 10;
        this.templateFile = mapping != null ? mapping.templateFile() : null;
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
        log.info("event={} name={} alias={}", LogEvent.ES_INDEX_CREATED, indexName, aliasName);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void ensureTemplateOnce() {
        if (templateCreated.get()) return;
        try {
            String json = resolveTemplateJson();
            esClient.indices().putIndexTemplate(t -> t.name(TEMPLATE_NAME).withJson(new StringReader(json)));
            log.info("event={} name={}", LogEvent.ES_TEMPLATE_CREATED, TEMPLATE_NAME);
            templateCreated.set(true);
        } catch (Exception e) {
            log.warn("event={} error={}", LogEvent.ES_TEMPLATE_ENSURE_FAILED, ErrorSanitizer.sanitize(e));
        }
    }

    private void ensureAlias(String indexName) throws Exception {
        if (!esClient.indices().existsAlias(r -> r.index(indexName).name(aliasName)).value())
            esClient.indices().putAlias(r -> r.index(indexName).name(aliasName));
    }

    /**
     * Loads the template from an external file if configured, otherwise uses
     * the built-in default. External files may use {@code ${INDEX_PREFIX}} as
     * a placeholder.
     */
    String resolveTemplateJson() {
        if (templateFile != null && !templateFile.isBlank()) {
            return loadExternalTemplate();
        }
        return defaultTemplateJson();
    }

    private String loadExternalTemplate() {
        try {
            String content = Files.readString(Path.of(templateFile));
            String resolved = content.replace("${INDEX_PREFIX}", indexPrefix);
            log.info("event={} path={}", LogEvent.ES_TEMPLATE_LOADED, templateFile);
            return resolved;
        } catch (IOException e) {
            log.warn("event={} path={} error={} reason=falling-back-to-default",
                    LogEvent.ES_TEMPLATE_LOAD_FAILED, templateFile, e.getMessage());
            return defaultTemplateJson();
        }
    }

    private String defaultTemplateJson() {
        return """
                {
                  "index_patterns": ["%s-*"],
                  "template": {
                    "settings": {
                      "index.mapping.total_fields.limit": %d,
                      "index.mapping.depth.limit": %d,
                      "analysis": {
                        "filter": {
                          "english_stop":    { "type": "stop",    "stopwords": "_english_" },
                          "english_stemmer": { "type": "stemmer", "language": "english" },
                          "beckn_synonyms":  { "type": "synonym", "lenient": true, "synonyms": ["ev, electric vehicle", "charger, charging station", "kw, kilowatt", "logistics, shipping, delivery", "parcel, package, shipment", "organic, natural", "vegan, plant-based", "discount, offer, deal", "cod, cash delivery"] }
                        },
                        "analyzer": {
                          "beckn_text": {
                            "type": "custom",
                            "tokenizer": "standard",
                            "filter": ["lowercase", "english_stop", "english_stemmer"]
                          },
                          "beckn_text_search": {
                            "type": "custom",
                            "tokenizer": "standard",
                            "filter": ["lowercase", "english_stop", "beckn_synonyms", "english_stemmer"]
                          }
                        }
                      }
                    },
                    "mappings": {
                      "dynamic_templates": [
                        { "geo_fields":               { "path_match": "*.geo",              "mapping": { "type": "geo_shape" } } },
                        { "resource_attrs_longs_as_float":   { "path_match": "resource_attributes.*", "match_mapping_type": "long",   "mapping": { "type": "float" } } },
                        { "resource_attrs_doubles_as_float": { "path_match": "resource_attributes.*", "match_mapping_type": "double", "mapping": { "type": "float" } } },
                        { "strings_as_keywords":      { "match_mapping_type": "string",  "mapping": { "type": "keyword" } } },
                        { "integers_as_ints":         { "match_mapping_type": "long",    "mapping": { "type": "integer" } } },
                        { "doubles_as_doubles":       { "match_mapping_type": "double",  "mapping": { "type": "double"  } } },
                        { "booleans":                 { "match_mapping_type": "boolean", "mapping": { "type": "boolean" } } }
                      ],
                      "properties": {
                        "catalog_id":              { "type": "keyword" },
                        "catalog_context":         { "type": "keyword" },
                        "catalog_type":            { "type": "keyword" },
                        "catalog_name":            { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "catalog_short_desc":      { "type": "text" },
                        "catalog_long_desc":       { "type": "text" },
                        "catalog_descriptor_thumbnail_image": { "type": "keyword" },
                        "catalog_descriptor_docs":            { "type": "nested" },
                        "catalog_descriptor_media_file":      { "type": "nested" },
                        "catalog_provider_id":     { "type": "keyword" },
                        "catalog_provider_name":   { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "catalog_is_active":       { "type": "boolean" },
                        "catalog_validity": {
                          "type": "object",
                          "properties": {
                            "@type":     { "type": "keyword" },
                            "startDate": { "type": "date" },
                            "endDate":   { "type": "date" },
                            "startTime": { "type": "keyword" },
                            "endTime":   { "type": "keyword" }
                          }
                        },
                        "resource_id":                 { "type": "keyword" },
                        "resource_context":            { "type": "keyword" },
                        "resource_type":               { "type": "keyword" },
                        "network_id":              { "type": "keyword" },
                        "schema_type":             { "type": "keyword" },
                        "resource_name":               { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "resource_short_desc":         { "type": "text" },
                        "resource_long_desc":          { "type": "text" },
                        "resource_provider_id":        { "type": "keyword" },
                        "resource_provider_name":      { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "resource_descriptor_thumbnail_image": { "type": "keyword" },
                        "resource_descriptor_docs":            { "type": "nested" },
                        "resource_descriptor_media_file":      { "type": "nested" },
                        "resource_category_code":      { "type": "keyword" },
                        "resource_category_name":      { "type": "text", "fields": { "raw": { "type": "keyword" } } },
                        "resource_rating_value":       { "type": "float" },
                        "resource_rating_count":       { "type": "integer" },
                        "resource_rating_review_text": { "type": "text" },
                        "resource_is_active":          { "type": "boolean" },
                        "resource_rateable":           { "type": "boolean" },
                        "resource_attributes": {
                          "type": "object",
                          "dynamic": true,
                          "properties": {
                            "@context": { "type": "keyword" },
                            "@type":    { "type": "keyword" }
                          }
                        },
                        "resource_attributes_type":    { "type": "keyword" },
                        "resource_attributes_context": { "type": "keyword" },
                        "constraints": {
                          "type": "nested",
                          "properties": {
                            "@type": { "type": "keyword" },
                            "name":  { "type": "keyword" },
                            "value": { "type": "keyword" }
                          }
                        },
                        "policies": {
                          "type": "nested",
                          "properties": {
                            "@type": { "type": "keyword" },
                            "name":  { "type": "keyword" }
                          }
                        },
                        "offers":                  { "type": "nested" },
                        "full_text_blob":          { "type": "text", "analyzer": "beckn_text", "search_analyzer": "beckn_text_search" },
                        "indexed_at":              { "type": "date" },
                        "resource_vector":             { "type": "dense_vector", "dims": 1536, "index": true, "similarity": "cosine" }
                      }
                    }
                  }
                }
                """
                .formatted(indexPrefix, totalFieldsLimit, depthLimit);
    }
}
