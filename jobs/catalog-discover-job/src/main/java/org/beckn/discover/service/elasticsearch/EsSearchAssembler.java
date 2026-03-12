package org.beckn.discover.service.elasticsearch;

import org.beckn.discover.config.AnyEsFeatureCondition;
import org.beckn.discover.model.Attributes;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.CategoryCode;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.Item;
import org.beckn.discover.model.Location;
import org.beckn.discover.model.Provider;
import org.beckn.discover.model.Rating;
import org.beckn.discover.service.response.CatalogProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles ES hit source maps (flat documents indexed by catalog-publish-job)
 * into {@link Catalog} objects grouped by {@code catalog_id}.
 *
 * <p>
 * Each ES hit is one item document. Multiple hits from the same catalog are
 * grouped into a single {@link Catalog} with multiple {@link Item}s.
 * After grouping, each catalog is normalised via
 * {@link CatalogProcessor#processCatalog}.
 * </p>
 */
@Component
@Conditional(AnyEsFeatureCondition.class)
public class EsSearchAssembler {

    private static final Logger log = LoggerFactory.getLogger(EsSearchAssembler.class);

    private final CatalogProcessor catalogProcessor;

    public EsSearchAssembler(CatalogProcessor catalogProcessor) {
        this.catalogProcessor = catalogProcessor;
    }

    /**
     * @param hits          list of raw ES hit source maps
     * @param transactionId for logging correlation
     * @return assembled and normalised catalogs; never null
     */
    @SuppressWarnings("unchecked")
    public List<Catalog> assemble(List<Map<String, Object>> hits, String transactionId) {
        if (hits.isEmpty())
            return List.of();

        // Group by catalog_id — preserves insertion order for deterministic output
        Map<String, Catalog> byCatalogId = new LinkedHashMap<>();

        for (Map<String, Object> doc : hits) {
            try {
                String catalogId = str(doc, "catalog_id");
                if (catalogId == null) {
                    log.warn("es.assembler.missing-catalog-id txId={}", transactionId);
                    continue;
                }

                Catalog catalog = byCatalogId.computeIfAbsent(catalogId, id -> buildCatalog(id, doc));
                catalog.getItems().add(buildItem(doc));
                mergeOffersFromDoc(catalog, doc);
            } catch (Exception e) {
                log.warn("es.assembler.hit.failed txId={} error={}", transactionId, e.getMessage());
            }
        }

        List<Catalog> result = new ArrayList<>(byCatalogId.size());
        for (Catalog raw : byCatalogId.values()) {
            Catalog processed = catalogProcessor.processCatalog(raw);
            if (processed != null)
                result.add(processed);
        }

        log.debug("es.assembler.done hits={} catalogs={} txId={}", hits.size(), result.size(), transactionId);
        return result;
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private static Catalog buildCatalog(String catalogId, Map<String, Object> doc) {
        Catalog catalog = new Catalog();
        catalog.setContext(str(doc, "catalog_context"));
        catalog.setType(str(doc, "catalog_type"));
        catalog.setId(catalogId);
        catalog.setBppId(str(doc, "bpp_id"));
        catalog.setBppUri(str(doc, "bpp_uri"));
        catalog.setDescriptor(new Descriptor("beckn:Descriptor"));
        catalog.setItems(new ArrayList<>());
        catalog.setOffers(new ArrayList<>());
        return catalog;
    }

    /**
     * Merges non-empty offers from an ES hit document into the catalog's offer
     * list.
     *
     * <p>
     * Called for every hit, not just the first, because each ES document carries
     * only the offers that apply to its specific item. An item with no applicable
     * offers has an empty {@code offers} array, while other items in the same
     * catalog
     * may carry the relevant offers. Accumulating across all hits ensures that
     * offers
     * are not lost when a non-matching item's document happens to arrive first.
     * </p>
     */
    @SuppressWarnings("unchecked")
    private static void mergeOffersFromDoc(Catalog catalog, Map<String, Object> doc) {
        Object offersRaw = doc.get("offers");
        if (offersRaw instanceof List<?> offerList && !offerList.isEmpty())
            catalog.getOffers().addAll((List<Object>) offerList);
    }

    private static Item buildItem(Map<String, Object> doc) {
        Item item = new Item();
        item.setContext(str(doc, "item_context"));
        item.setType(str(doc, "item_type"));
        item.setId(str(doc, "item_id"));
        item.setDescriptor(buildDescriptor(doc));
        item.setCategory(buildCategory(doc));
        item.setRating(buildRating(doc));
        item.setRateable(bool(doc, "item_rateable"));
        item.setIsActive(bool(doc, "item_is_active"));
        item.setProvider(buildProvider(doc));
        item.setItemAttributes(buildAttributes(doc));

        // Reconstruct all availableAt locations from loc_catalogs_beckn_items_beckn_availableAt.geo
        item.setAvailableAt(buildAvailableAt(doc));

        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<Location> buildAvailableAt(Map<String, Object> doc) {
        Object locRaw = doc.get("loc_catalogs_beckn_items_beckn_availableAt");

        // loc_* is stored directly as a Location object (single) or List (multiple)
        List<Map<String, Object>> locList;
        if (locRaw instanceof List<?> list) {
            locList = (List<Map<String, Object>>) list;
        } else if (locRaw instanceof Map<?, ?> single) {
            locList = List.of((Map<String, Object>) single);
        } else {
            return null;
        }

        List<Location> locations = new ArrayList<>();
        for (Map<String, Object> locObj : locList) {
            // Reconstruct geo
            Object geoRaw = locObj.get("geo");
            if (!(geoRaw instanceof Map<?, ?> geoMap)) continue;
            String geoType = (String) geoMap.get("type");
            Object coords = geoMap.get("coordinates");
            if (geoType == null || !(coords instanceof List<?>)) continue;
            Location.Geo geo = new Location.Geo(geoType, (List<Object>) coords);

            // Reconstruct address if present
            Location.Address address = null;
            Object addrRaw = locObj.get("address");
            if (addrRaw instanceof Map<?, ?> addrMap) {
                address = new Location.Address();
                address.setStreetAddress((String) addrMap.get("streetAddress"));
                address.setAddressLocality((String) addrMap.get("addressLocality"));
                address.setAddressRegion((String) addrMap.get("addressRegion"));
                address.setPostalCode((String) addrMap.get("postalCode"));
                address.setAddressCountry((String) addrMap.get("addressCountry"));
            }

            locations.add(new Location("beckn:Location", geo, address));
        }
        return locations.isEmpty() ? null : locations;
    }

    private static Descriptor buildDescriptor(Map<String, Object> doc) {
        Descriptor d = new Descriptor("beckn:Descriptor");
        d.setName(str(doc, "item_name"));
        d.setShortDesc(str(doc, "item_short_desc"));
        d.setLongDesc(str(doc, "item_long_desc"));
        return d;
    }

    private static CategoryCode buildCategory(Map<String, Object> doc) {
        String code = str(doc, "item_category_code");
        if (code == null)
            return null;
        CategoryCode cat = new CategoryCode("schema:CategoryCode", code);
        cat.setName(str(doc, "item_category_name"));
        return cat;
    }

    private static Rating buildRating(Map<String, Object> doc) {
        Object ratingValue = doc.get("item_rating_value");
        Object ratingCount = doc.get("item_rating_count");
        if (ratingValue == null && ratingCount == null)
            return null;
        Rating r = new Rating("beckn:Rating");
        if (ratingValue instanceof Number n)
            r.setRatingValue(n.doubleValue());
        if (ratingCount instanceof Number n)
            r.setRatingCount(n.intValue());
        return r;
    }

    private static Provider buildProvider(Map<String, Object> doc) {
        String providerId = str(doc, "item_provider_id");
        if (providerId == null)
            return null;
        Descriptor desc = new Descriptor("beckn:Descriptor");
        desc.setName(str(doc, "item_provider_name"));
        return new Provider(providerId, desc);
    }

    @SuppressWarnings("unchecked")
    private static Attributes buildAttributes(Map<String, Object> doc) {
        Object attrsRaw = doc.get("item_attributes");
        if (attrsRaw instanceof Map<?, ?> map) {
            Attributes attrs = new Attributes(
                    (String) map.get("@context"),
                    (String) map.get("@type"));
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (!k.startsWith("@"))
                    attrs.setAttribute(k, v);
            });
            return attrs;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Boolean bool(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        return v instanceof Boolean b ? b : null;
    }
}
