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
import org.beckn.discover.common.BecknFields;
import org.beckn.discover.service.response.CatalogProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
        catalog.setDescriptor(new Descriptor("Descriptor"));
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

    @SuppressWarnings("unchecked")
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
        Provider provider = buildProvider(doc);
        if (provider != null) {
            provider.setLocations(collectProviderLocations(doc));
        }
        item.setProvider(provider);
        item.setItemAttributes(buildAttributes(doc));

        // Reconstruct direct item-level locations from loc_* fields
        item.setAvailableAt(collectItemLocations(doc));

        // v2.1: constraints and policies — present only when indexed
        Object constraintsRaw = doc.get("constraints");
        if (constraintsRaw instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> constraints = (List<Map<String, Object>>) list;
            item.setConstraints(constraints);
        }
        Object policiesRaw = doc.get("policies");
        if (policiesRaw instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> policies = (List<Map<String, Object>>) list;
            item.setPolicies(policies);
        }

        return item;
    }

    /**
     * Collects only <b>direct item-level</b> location fields from the ES document.
     *
     * <p>{@code GeoShapeExtractor} on the publish side indexes location objects from
     * any path as {@code loc_*} fields. However, offer-level, provider-level,
     * itemAttributes-level, and providerAttributes-level locations are returned via
     * their own response structures. Only direct item children
     * (e.g. {@code beckn:availableAt}, {@code beckn:location}, or any spec-extended
     * location field) should be collected here.</p>
     */
    private static List<Location> collectItemLocations(Map<String, Object> doc) {
        return collectLocFields(doc, key ->
                key.contains("_items_")
                        && !key.contains("_provider_")
                        && !key.contains("_providerAttributes_")
                        && !key.contains("_itemAttributes_")
                        && !key.contains("_offers_"));
    }

    /**
     * Collects provider-level location fields (e.g.
     * {@code loc_catalogs_beckn_items_beckn_provider_beckn_locations})
     * for {@link Provider#setLocations}.
     */
    private static List<Location> collectProviderLocations(Map<String, Object> doc) {
        return collectLocFields(doc, key ->
                key.contains("_provider_")
                        && !key.contains("_providerAttributes_"));
    }

    @SuppressWarnings("unchecked")
    private static List<Location> collectLocFields(Map<String, Object> doc,
                                                    Predicate<String> keyFilter) {
        List<Location> locations = new ArrayList<>();

        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("loc_") || !keyFilter.test(key)) continue;

            Object locRaw = entry.getValue();
            List<Map<String, Object>> locList;
            if (locRaw instanceof List<?> list) {
                locList = (List<Map<String, Object>>) list;
            } else if (locRaw instanceof Map<?, ?> single) {
                locList = List.of((Map<String, Object>) single);
            } else {
                continue;
            }

            for (Map<String, Object> locObj : locList) {
                Location location = reconstructLocation(locObj);
                if (location != null) locations.add(location);
            }
        }
        return locations.isEmpty() ? null : locations;
    }

    @SuppressWarnings("unchecked")
    private static Location reconstructLocation(Map<String, Object> locObj) {
        Object geoRaw = locObj.get("geo");
        if (!(geoRaw instanceof Map<?, ?> geoMap)) return null;
        String geoType = (String) geoMap.get("type");
        Object coords = geoMap.get("coordinates");
        if (geoType == null || !(coords instanceof List<?>)) return null;
        Location.Geo geo = new Location.Geo(geoType, (List<Object>) coords);

        Location.Address address = null;
        Object addrRaw = locObj.get("address");
        if (addrRaw instanceof Map<?, ?> addrMap) {
            address = new Location.Address();
            address.setStreetAddress((String) addrMap.get("streetAddress"));
            address.setExtendedAddress((String) addrMap.get("extendedAddress"));
            address.setAddressLocality((String) addrMap.get("addressLocality"));
            address.setAddressRegion((String) addrMap.get("addressRegion"));
            address.setPostalCode((String) addrMap.get("postalCode"));
            address.setAddressCountry((String) addrMap.get("addressCountry"));
        }

        return new Location("Location", geo, address);
    }

    @SuppressWarnings("unchecked")
    private static Descriptor buildDescriptor(Map<String, Object> doc) {
        Descriptor d = new Descriptor("Descriptor");
        d.setName(str(doc, "item_name"));
        d.setShortDesc(str(doc, "item_short_desc"));
        d.setLongDesc(str(doc, "item_long_desc"));
        Object imgRaw = doc.get("item_image");
        if (imgRaw instanceof List<?> list && !list.isEmpty())
            d.setImage((List<String>) list);
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
        Rating r = new Rating("Rating");
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
        Descriptor desc = new Descriptor("Descriptor");
        desc.setName(str(doc, "item_provider_name"));
        return new Provider(providerId, desc);
    }

    @SuppressWarnings("unchecked")
    private static Attributes buildAttributes(Map<String, Object> doc) {
        Object attrsRaw = doc.get("item_attributes");
        if (attrsRaw instanceof Map<?, ?> map) {
            // Prefer the dedicated top-level ES fields for @type and @context when present,
            // so that keyword filtering against item_attributes_type works correctly.
            String atType = doc.containsKey("item_attributes_type")
                    ? (String) doc.get("item_attributes_type")
                    : (String) map.get(BecknFields.AT_TYPE);
            String atContext = doc.containsKey("item_attributes_context")
                    ? (String) doc.get("item_attributes_context")
                    : (String) map.get(BecknFields.AT_CONTEXT);
            Attributes attrs = new Attributes(atContext, atType);
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (!k.equals(BecknFields.AT_CONTEXT) && !k.equals(BecknFields.AT_TYPE))
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
