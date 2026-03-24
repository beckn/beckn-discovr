package org.beckn.discover.service.nlweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.NLWebResponse;
import org.beckn.discover.service.response.CatalogProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Transforms a raw NLWeb JSON response string into a list of
 * {@link Catalog} objects ready for the
 * {@link org.beckn.discover.service.response.CatalogPipeline}.
 *
 * <h3>NLWeb response shapes handled</h3>
 * <ol>
 *   <li><b>Object</b> — {@code {"content": [...]}}</li>
 *   <li><b>Outer array</b> — each element is an object with a {@code content}
 *       array of result items</li>
 *   <li><b>Nested array</b> — each outer element is itself an array of
 *       assistant messages; each message has {@code sender_type=assistant},
 *       {@code message_type=result}, and a {@code content} array</li>
 * </ol>
 *
 * <h3>Score filtering</h3>
 * Items below {@code discovery.nlweb.score-threshold} (default 80) are
 * silently skipped and logged at DEBUG.
 *
 * <h3>Catalog merging</h3>
 * Multiple content items from the same provider are merged into a single
 * {@link Catalog} via {@link CatalogProcessor#mergeCatalogsByProvider}.
 * This is NLWeb-specific: PostgreSQL groups by {@code catalog_id} in SQL.
 */
@Component
public class NLWebAssembler {

    private static final Logger log = LoggerFactory.getLogger(NLWebAssembler.class);

    private final ObjectMapper          objectMapper;
    private final CatalogProcessor      catalogProcessor;
    private final DiscoveryProperties   properties;

    public NLWebAssembler(
            ObjectMapper        objectMapper,
            CatalogProcessor    catalogProcessor,
            DiscoveryProperties properties) {
        this.objectMapper     = objectMapper;
        this.catalogProcessor = catalogProcessor;
        this.properties       = properties;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Parses the NLWeb JSON response, filters by score, normalises each
     * catalog, and merges by provider.
     *
     * @param nlWebResponse raw JSON string from the NLWeb API
     * @param transactionId used for logging correlation
     * @return assembled catalogs; never {@code null}, may be empty
     * @throws Exception if JSON parsing fails fatally
     */
    public List<Catalog> assemble(String nlWebResponse, String transactionId) throws Exception {
        if (nlWebResponse == null || nlWebResponse.isBlank()) {
            log.warn("nlweb.assembler.empty transactionId={}", transactionId);
            return List.of();
        }

        log.debug("nlweb.assembler.start transactionId={}", transactionId);
        long t0 = System.nanoTime();

        int scoreThreshold = scoreThreshold();
        List<NLWebResponse.ContentItem> contentItems = extractContentItems(nlWebResponse, scoreThreshold, transactionId);

        if (contentItems.isEmpty()) {
            log.warn("nlweb.assembler.no-items transactionId={}", transactionId);
            return List.of();
        }

        List<Catalog> rawCatalogs = toCatalogs(contentItems, transactionId);
        List<Catalog> merged = catalogProcessor.mergeCatalogsByProvider(rawCatalogs);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("nlweb.assembler.done contentItems={} rawCatalogs={} mergedCatalogs={} durationMs={} transactionId={}",
                contentItems.size(), rawCatalogs.size(), merged.size(), ms, transactionId);

        return merged;
    }

    // ── Content item extraction ───────────────────────────────────────────────

    private List<NLWebResponse.ContentItem> extractContentItems(
            String json, int threshold, String txId) throws Exception {

        String trimmed = json.trim();
        List<NLWebResponse.ContentItem> collected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        if (trimmed.startsWith("[")) {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isArray()) {
                for (JsonNode element : root) {
                    if (element.isObject() && element.has("content") && element.get("content").isArray()) {
                        collectFromContentArray(element.get("content"), collected, skipped, threshold);
                    } else if (element.isArray()) {
                        // Nested-array streaming format
                        StreamSupport.stream(element.spliterator(), false)
                                .filter(msg -> msg.isObject()
                                        && "assistant".equals(asText(msg, "sender_type"))
                                        && "result".equals(asText(msg, "message_type"))
                                        && msg.has("content") && msg.get("content").isArray())
                                .forEach(msg -> collectFromContentArray(
                                        msg.get("content"), collected, skipped, threshold));
                    }
                }
            }
        } else {
            NLWebResponse response = objectMapper.readValue(trimmed, NLWebResponse.class);
            if (response.getContent() != null) {
                response.getContent().forEach(item -> addOrSkip(item, collected, skipped, threshold));
            }
        }

        log.debug("nlweb.extract.done accepted={} skipped={} transactionId={}", collected.size(), skipped.size(), txId);
        if (!skipped.isEmpty()) {
            skipped.forEach(s -> log.debug("nlweb.item.skipped reason={} transactionId={}", s, txId));
        }
        return collected;
    }

    private void collectFromContentArray(
            JsonNode contentArray,
            List<NLWebResponse.ContentItem> collected,
            List<String> skipped,
            int threshold) {
        for (JsonNode itemNode : contentArray) {
            try {
                JsonNode normalizedNode = normalizeCatalogNodes(itemNode);
                NLWebResponse.ContentItem item = objectMapper.treeToValue(normalizedNode, NLWebResponse.ContentItem.class);
                addOrSkip(item, collected, skipped, threshold);
            } catch (Exception e) {
                skipped.add("parse-failed:" + e.getMessage());
            }
        }
    }

    /**
     * All NLWeb catalog nodes are v2.1 (upstream rejects v2.0 payloads).
     * Returns the node unchanged.
     */
    private static JsonNode normalizeCatalogNodes(JsonNode contentItemNode) {
        return contentItemNode;
    }

    private void addOrSkip(
            NLWebResponse.ContentItem item,
            List<NLWebResponse.ContentItem> collected,
            List<String> skipped,
            int threshold) {
        String reason = skipReason(item, threshold);
        if (reason == null) {
            collected.add(item);
        } else {
            skipped.add(String.format("'%s' score=%s reason=%s", item != null ? item.getName() : "null",
                    item != null ? item.getScore() : "null", reason));
        }
    }

    private static String skipReason(NLWebResponse.ContentItem item, int threshold) {
        if (item == null) return "null-item";
        if (item.getScore() == null) return "null-score";
        if (item.getScore() < threshold) return "low-score";
        if (item.getSchemaObject() == null) return "missing-schema-object";
        if (item.getSchemaObject().getCatalogs() == null) return "missing-catalogs";
        if (item.getSchemaObject().getCatalogs().isEmpty()) return "empty-catalogs";
        return null; // valid item
    }

    // ── Catalog transformation ────────────────────────────────────────────────

    private List<Catalog> toCatalogs(List<NLWebResponse.ContentItem> items, String txId) {
        List<Catalog> all = new ArrayList<>(items.size() * 2);
        for (NLWebResponse.ContentItem item : items) {
            try {
                for (Catalog raw : item.getSchemaObject().getCatalogs()) {
                    Catalog processed = catalogProcessor.processCatalog(raw);
                    if (processed != null) all.add(processed);
                }
            } catch (Exception e) {
                log.warn("nlweb.catalog.transform.failed name={} transactionId={} error={}",
                        item.getName(), txId, e.getMessage());
            }
        }
        return all;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int scoreThreshold() {
        return (properties != null && properties.getNlweb() != null)
                ? properties.getNlweb().getScoreThreshold()
                : 80;
    }

    private static String asText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
