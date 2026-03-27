package org.beckn.discover.service.postgresql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.Resource;
import org.beckn.discover.model.TimePeriod;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.util.DiscoveryConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Transforms raw JDBC rows (from PostgreSQL / YugabyteDB) into a list of
 * {@link Catalog} objects ready for the {@link org.beckn.discover.service.response.CatalogPipeline}.
 *
 * <h3>Row shape</h3>
 * <pre>
 * id            TEXT  — item primary key
 * catalog_id    TEXT  — used as the catalog grouping key
 * item_payload  JSONB — full item JSON (may be PGobject or String at runtime)
 * matching_offers JSONB — optional; present only for selection-path JSONPath queries
 *                         ({@link QueryBuilderHelper#MATCHING_OFFERS_ALIAS})
 * </pre>
 *
 * <h3>Grouping strategy</h3>
 * Items are grouped by {@code catalog_id}.  The catalog-level metadata
 * (context, type, descriptor, validity, bppId, bppUri, providerId) is
 * extracted from the first row that carries a catalog payload inside
 * {@code item_payload.catalogs[0]}.  All subsequent rows for the same
 * {@code catalog_id} only contribute items / offers — the catalog metadata
 * is not re-parsed.
 *
 * <h3>Performance notes</h3>
 * <ul>
 *   <li>Catalog map uses an initial capacity of 16 to avoid resizing on typical
 *       result sets.</li>
 *   <li>Item / offer lists are pre-sized at 16 / 8 respectively.</li>
 *   <li>JSON parsing uses {@code objectMapper.readTree(value.toString())}
 *       once per row — the result is reused for both item and catalog
 *       extraction within the same row.</li>
 *   <li>Offer nodes use {@code convertValue} (no intermediate token stream).</li>
 * </ul>
 */
@Component
public class PostgreSQLAssembler {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLAssembler.class);

    private final ObjectMapper objectMapper;

    public PostgreSQLAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Converts JDBC result rows into a list of partially-assembled
     * {@link Catalog} objects.
     *
     * <p>The returned catalogs have items and offers populated but have
     * <em>not</em> been through the shared
     * {@link org.beckn.discover.service.response.CatalogPipeline} yet —
     * calling code must run the pipeline before building the response.</p>
     *
     * @param rows    raw JDBC rows; {@code null} is treated as empty
     * @param request used only for logging context (transactionId)
     * @return assembled catalogs; never {@code null}, may be empty
     */
    public List<Catalog> assemble(List<Map<String, Object>> rows, QueryRequest request) {
        if (rows == null || rows.isEmpty()) {
            log.debug("assembler.empty transactionId={}", request.transactionId());
            return List.of();
        }

        log.debug("assembler.start rows={} transactionId={}", rows.size(), request.transactionId());
        long t0 = System.nanoTime();

        Map<String, Catalog> catalogMap = new HashMap<>(16);
        int skipped = 0;

        for (Map<String, Object> row : rows) {
            try {
                if (!processRow(row, catalogMap)) skipped++;
            } catch (Exception e) {
                log.warn("assembler.row.error transactionId={} error={}", request.transactionId(), e.getMessage(), e);
                skipped++;
            }
        }

        List<Catalog> catalogs = new ArrayList<>(catalogMap.values());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        log.info("assembler.done catalogs={} resources={} skippedRows={} durationMs={} transactionId={}",
                catalogs.size(),
                catalogs.stream().mapToInt(c -> c.getResources() != null ? c.getResources().size() : 0).sum(),
                skipped, elapsedMs, request.transactionId());

        return catalogs;
    }

    // ── Row processing ───────────────────────────────────────────────────────

    /**
     * Processes one JDBC row and merges it into {@code catalogMap}.
     *
     * @return {@code true} if the row contributed to a catalog; {@code false}
     *         if the row was skipped due to missing/unparseable data
     */
    private boolean processRow(Map<String, Object> row, Map<String, Catalog> catalogMap) throws Exception {
        String catalogId = (String) row.get("catalog_id");
        if (catalogId == null || catalogId.isBlank()) {
            log.warn("assembler.row.skip reason=missing-catalog-id");
            return false;
        }

        String itemId = (String) row.get("id");
        JsonNode itemPayload = toJsonNode(row.get("item_payload"));
        if (itemPayload == null) {
            log.warn("assembler.row.skip reason=null-payload itemId={}", itemId);
            return false;
        }

        JsonNode itemNode = extractItemNode(itemId, itemPayload);
        if (itemNode == null) {
            log.warn("assembler.row.skip reason=item-not-found itemId={}", itemId);
            return false;
        }

        Resource resource = objectMapper.treeToValue(itemNode, Resource.class);
        if (resource == null) {
            log.warn("assembler.row.skip reason=resource-deserialise-failed itemId={}", itemId);
            return false;
        }

        JsonNode catalogPayload = extractCatalogPayload(itemPayload);

        // computeIfAbsent: catalog metadata is extracted only on the first row
        Catalog catalog = catalogMap.computeIfAbsent(catalogId, id -> buildCatalog(id, catalogPayload));
        catalog.getResources().add(resource);

        // Back-fill providerId from resource when catalog payload lacks providerId
        if (catalog.getProviderId() == null
                && resource.getProvider() != null
                && resource.getProvider().getId() != null
                && !resource.getProvider().getId().isBlank()) {
            catalog.setProviderId(resource.getProvider().getId());
        }

        // Offer extraction — uses matching_offers when present, falls back to catalog payload
        mergeOffersFromRow(catalog, row, catalogPayload);

        return true;
    }

    // ── Catalog construction ─────────────────────────────────────────────────

    private Catalog buildCatalog(String catalogId, JsonNode catalogPayload) {
        Catalog catalog = new Catalog();
        catalog.setId(catalogId);
        catalog.setResources(new ArrayList<>(16));
        catalog.setOffers(new ArrayList<>(8));

        if (catalogPayload != null) {
            extractCatalogAttributes(catalog, catalogPayload);
        }
        return catalog;
    }

    private void extractCatalogAttributes(Catalog catalog, JsonNode cp) {
        try {
            setTextIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_ID,          catalog::setId);
            setTextIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_PROVIDER_ID,  catalog::setProviderId);
            setTextIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_BPP_ID,       catalog::setBppId);
            setTextIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_BPP_URI,      catalog::setBppUri);
            parseIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_DESCRIPTOR, Descriptor.class, catalog::setDescriptor);
            parseIfPresent(cp, DiscoveryConstants.JsonFields.BECKN_VALIDITY,   TimePeriod.class,  catalog::setValidity);
            // Note: offers are NOT merged here — they are merged exactly once per catalog
            // in mergeOffersFromRow (first row guard) to avoid N-row duplication.
        } catch (Exception e) {
            log.warn("assembler.catalog.attributes.error catalogId={} error={}", catalog.getId(), e.getMessage());
        }
    }

    // ── Offer merging ────────────────────────────────────────────────────────

    /**
     * Merges offers into the catalog from the JDBC row.
     * Preference order:
     * <ol>
     *   <li>{@code matching_offers} column — present only for selection-path
     *       (offer-scoped) queries; accumulated across all matching rows.</li>
     *   <li>{@code offers} from the catalog payload — static offers.
     *       Merged <b>only on the first row</b> (when the offers list is still
     *       empty) to avoid N-row duplication when a catalog has multiple items.</li>
     * </ol>
     */
    private void mergeOffersFromRow(Catalog catalog, Map<String, Object> row, JsonNode catalogPayload) {
        Object filterResult = row.get(QueryBuilderHelper.MATCHING_OFFERS_ALIAS);
        if (filterResult != null) {
            JsonNode filterNode = toJsonNode(filterResult);
            if (filterNode != null && filterNode.isArray() && !filterNode.isEmpty()
                    && isOfferLike(filterNode.get(0))) {
                mergeOffers(catalog, filterNode);
                return;
            }
        }
        // Fallback: static offers from the catalog payload — always merge across all rows.
        // Duplicates (same offer in every item row) are removed by
        // CatalogPipeline.step2DeduplicateOffers which deduplicates by id.
        if (catalogPayload != null
                && catalogPayload.has(DiscoveryConstants.DEFAULT_OFFER_ATTRIBUTE)) {
            mergeOffers(catalog, catalogPayload.get(DiscoveryConstants.DEFAULT_OFFER_ATTRIBUTE));
        }
    }

    private void mergeOffers(Catalog catalog, JsonNode offersNode) {
        if (offersNode == null || offersNode.isNull()) return;
        if (offersNode.isArray()) {
            offersNode.forEach(node -> addOfferSafe(catalog, node));
        } else {
            addOfferSafe(catalog, offersNode);
        }
    }

    private void addOfferSafe(Catalog catalog, JsonNode offerNode) {
        try {
            catalog.getOffers().add(objectMapper.convertValue(offerNode, Object.class));
        } catch (Exception e) {
            log.debug("assembler.offer.skip error={}", e.getMessage());
        }
    }

    /**
     * Returns {@code true} when the node looks like a Beckn Offer.
     *
     * <p>Offers carry {@code offerAttributes}; resources carry {@code resourceAttributes}.
     * Falls back to {@code resourceIds} as a secondary signal.</p>
     */
    private static boolean isOfferLike(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        if (node.has("offerAttributes")) return true;
        if (node.has("resourceAttributes")) return false;
        return node.has("resourceIds");
    }

    // ── JSON extraction helpers ──────────────────────────────────────────────

    /**
     * Extracts the item {@link JsonNode} from the payload.
     * Item lives inside {@code payload.catalogs[0].resources[*]}.
     */
    private JsonNode extractItemNode(String itemId, JsonNode itemPayload) {
        if (itemPayload == null) return null;

        // Item lives inside catalogs array
        JsonNode catalogsNode = itemPayload.get(DiscoveryConstants.JsonFields.CATALOGS);
        if (catalogsNode == null || !catalogsNode.isArray()) return null;

        return StreamSupport.stream(catalogsNode.spliterator(), false)
                .map(cat -> {
                    return cat.get(DiscoveryConstants.JsonFields.BECKN_RESOURCES);
                })
                .filter(items -> items != null && items.isArray())
                .flatMap(items -> StreamSupport.stream(items.spliterator(), false))
                .filter(node -> itemId != null
                        && itemId.equals(node.path(DiscoveryConstants.JsonFields.BECKN_ID).asText()))
                .findFirst()
                .orElse(null);
    }

    /** Returns the first element of {@code payload.catalogs}, or {@code null}. */
    private static JsonNode extractCatalogPayload(JsonNode itemPayload) {
        if (!itemPayload.has(DiscoveryConstants.JsonFields.CATALOGS)) return null;
        JsonNode catalogsNode = itemPayload.get(DiscoveryConstants.JsonFields.CATALOGS);
        return (catalogsNode.isArray() && !catalogsNode.isEmpty()) ? catalogsNode.get(0) : null;
    }

    /**
     * Converts any JDBC column value to a {@link JsonNode}.
     * Handles {@code String}, {@code PGobject} (via {@code toString()}), and
     * pre-parsed {@code JsonNode} references.
     */
    private JsonNode toJsonNode(Object value) {
        if (value == null) return null;
        if (value instanceof JsonNode node) return node;
        try {
            return objectMapper.readTree(value.toString());
        } catch (Exception e) {
            log.debug("assembler.json.parse.failed type={} error={}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ── Functional helpers ────────────────────────────────────────────────────

    private void setTextIfPresent(JsonNode node, String field, Consumer<String> setter) {
        if (node.has(field)) setter.accept(node.get(field).asText());
    }

    private <T> void parseIfPresent(JsonNode node, String field, Class<T> type, Consumer<T> setter) {
        if (!node.has(field)) return;
        try {
            setter.accept(objectMapper.treeToValue(node.get(field), type));
        } catch (Exception e) {
            log.debug("assembler.field.parse.failed field={} error={}", field, e.getMessage());
        }
    }
}
