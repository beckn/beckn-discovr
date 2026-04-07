package org.beckn.discover.service.elasticsearch;

import org.beckn.discover.config.AnyEsFeatureCondition;
import org.beckn.discover.model.Attributes;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.CategoryCode;
import org.beckn.discover.model.Constraint;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.Location;
import org.beckn.discover.model.Policy;
import org.beckn.discover.model.Provider;
import org.beckn.discover.model.Rating;
import org.beckn.discover.model.Resource;
import org.beckn.discover.model.TimePeriod;
import org.beckn.discover.common.BecknFields;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.response.CatalogProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.value;

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
 * Each ES hit is one resource document. Multiple hits from the same catalog are
 * grouped into a single {@link Catalog} with multiple {@link Resource}s.
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
                    log.warn(LogEvent.ES_SEARCH_COMPLETED + ".assembler-missing-catalog-id",
                            value("transactionId", transactionId));
                    continue;
                }

                Catalog catalog = byCatalogId.computeIfAbsent(catalogId, id -> buildCatalog(id, doc));
                catalog.getResources().add(buildResource(doc));
                mergeOffersFromDoc(catalog, doc);
            } catch (Exception e) {
                log.warn(LogEvent.ES_SEARCH_FAILED + ".assembler-hit",
                        value("transactionId", transactionId),
                        value("error", e.getMessage()));
            }
        }

        List<Catalog> result = new ArrayList<>(byCatalogId.size());
        for (Catalog raw : byCatalogId.values()) {
            Catalog processed = catalogProcessor.processCatalog(raw);
            if (processed != null)
                result.add(processed);
        }

        log.debug(LogEvent.ES_SEARCH_COMPLETED + ".assembled",
                value("hits", hits.size()),
                value("catalogs", result.size()),
                value("transactionId", transactionId));
        return result;
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Catalog buildCatalog(String catalogId, Map<String, Object> doc) {
        Catalog catalog = new Catalog();
        catalog.setId(catalogId);
        catalog.setBppId(str(doc, "bpp_id"));
        catalog.setBppUri(str(doc, "bpp_uri"));
        catalog.setDescriptor(buildCatalogDescriptor(doc));
        catalog.setProviderId(str(doc, "catalog_provider_id"));
        catalog.setResources(new ArrayList<>());
        catalog.setOffers(new ArrayList<>());
        Object validityRaw = doc.get("catalog_validity");
        if (validityRaw instanceof Map<?, ?> validityMap) {
            catalog.setValidity(timePeriodFromMap((Map<String, Object>) validityMap));
        }
        return catalog;
    }

    @SuppressWarnings("unchecked")
    private static Descriptor buildCatalogDescriptor(Map<String, Object> doc) {
        Descriptor d = new Descriptor();
        d.setName(str(doc, "catalog_name"));
        d.setShortDesc(str(doc, "catalog_short_desc"));
        d.setLongDesc(str(doc, "catalog_long_desc"));
        d.setThumbnailImage(str(doc, "catalog_descriptor_thumbnail_image"));
        Object docsRaw = doc.get("catalog_descriptor_docs");
        if (docsRaw instanceof List<?> list && !list.isEmpty())
            d.setDocs((List<Map<String, Object>>) list);
        Object mediaRaw = doc.get("catalog_descriptor_media_file");
        if (mediaRaw instanceof List<?> list && !list.isEmpty())
            d.setMediaFile((List<Map<String, Object>>) list);
        return d;
    }

    private static TimePeriod timePeriodFromMap(Map<String, Object> map) {
        TimePeriod tp = new TimePeriod();
        if (map.get("startDate") instanceof String s) {
            try { tp.setStartDate(java.time.OffsetDateTime.parse(s)); } catch (Exception ignored) {}
        }
        if (map.get("endDate") instanceof String s) {
            try { tp.setEndDate(java.time.OffsetDateTime.parse(s)); } catch (Exception ignored) {}
        }
        if (map.get("startTime") instanceof String s) tp.setStartTime(s);
        if (map.get("endTime") instanceof String s) tp.setEndTime(s);
        return tp;
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
    private static Resource buildResource(Map<String, Object> doc) {
        Resource resource = new Resource();
        resource.setId(str(doc, "resource_id"));
        resource.setDescriptor(buildDescriptor(doc));
        resource.setCategory(buildCategory(doc));
        resource.setRating(buildRating(doc));
        resource.setRateable(bool(doc, "resource_rateable"));
        resource.setIsActive(bool(doc, "resource_is_active"));
        Provider provider = buildProvider(doc);
        if (provider != null) {
            provider.setLocations(collectProviderLocations(doc));
        }
        resource.setProvider(provider);
        resource.setResourceAttributes(buildAttributes(doc));

        // Reconstruct direct resource-level locations from loc_* fields
        resource.setAvailableAt(collectItemLocations(doc));

        // v2.1: constraints and policies — present only when indexed
        Object constraintsRaw = doc.get("constraints");
        if (constraintsRaw instanceof List<?> list && !list.isEmpty()) {
            List<Constraint> constraints = list.stream()
                    .filter(e -> e instanceof Map<?, ?>)
                    .map(e -> constraintFromMap((Map<String, Object>) e))
                    .toList();
            if (!constraints.isEmpty()) resource.setConstraints(constraints);
        }
        Object policiesRaw = doc.get("policies");
        if (policiesRaw instanceof List<?> list && !list.isEmpty()) {
            List<Policy> policies = list.stream()
                    .filter(e -> e instanceof Map<?, ?>)
                    .map(e -> policyFromMap((Map<String, Object>) e))
                    .toList();
            if (!policies.isEmpty()) resource.setPolicies(policies);
        }

        return resource;
    }

    /**
     * Collects only <b>direct resource-level</b> location fields from the ES document.
     *
     * <p>{@code GeoShapeExtractor} on the publish side indexes location objects from
     * any path as {@code loc_*} fields. However, offer-level, provider-level,
     * resourceAttributes-level, and providerAttributes-level locations are returned via
     * their own response structures. Only direct resource children
     * (e.g. {@code availableAt}, {@code location}, or any spec-extended
     * location field) should be collected here.</p>
     */
    private static List<Location> collectItemLocations(Map<String, Object> doc) {
        return collectLocFields(doc, key ->
                key.contains("_resources_")
                        && !key.contains("_provider_")
                        && !key.contains("_providerAttributes_")
                        && !key.contains("_resourceAttributes_")
                        && !key.contains("_offers_"));
    }

    /**
     * Collects provider-level location fields (e.g.
     * {@code loc_catalogs_resources_provider_locations})
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

        return new Location(geo, address);
    }

    @SuppressWarnings("unchecked")
    private static Descriptor buildDescriptor(Map<String, Object> doc) {
        Descriptor d = new Descriptor();
        d.setName(str(doc, "resource_name"));
        d.setShortDesc(str(doc, "resource_short_desc"));
        d.setLongDesc(str(doc, "resource_long_desc"));
        d.setThumbnailImage(str(doc, "resource_descriptor_thumbnail_image"));
        Object docsRaw = doc.get("resource_descriptor_docs");
        if (docsRaw instanceof List<?> list && !list.isEmpty())
            d.setDocs((List<Map<String, Object>>) list);
        Object mediaRaw = doc.get("resource_descriptor_media_file");
        if (mediaRaw instanceof List<?> list && !list.isEmpty())
            d.setMediaFile((List<Map<String, Object>>) list);
        return d;
    }

    private static CategoryCode buildCategory(Map<String, Object> doc) {
        String code = str(doc, "resource_category_code");
        if (code == null)
            return null;
        CategoryCode cat = new CategoryCode(code);
        cat.setName(str(doc, "resource_category_name"));
        return cat;
    }

    private static Rating buildRating(Map<String, Object> doc) {
        Object ratingValue = doc.get("resource_rating_value");
        Object ratingCount = doc.get("resource_rating_count");
        String reviewText = str(doc, "resource_rating_review_text");
        if (ratingValue == null && ratingCount == null && reviewText == null)
            return null;
        Rating r = new Rating();
        if (ratingValue instanceof Number n)
            r.setRatingValue(n.doubleValue());
        if (ratingCount instanceof Number n)
            r.setRatingCount(n.intValue());
        r.setReviewText(reviewText);
        return r;
    }

    private static Provider buildProvider(Map<String, Object> doc) {
        String providerId = str(doc, "resource_provider_id");
        if (providerId == null)
            return null;
        Descriptor desc = new Descriptor();
        desc.setName(str(doc, "resource_provider_name"));
        return new Provider(providerId, desc);
    }

    @SuppressWarnings("unchecked")
    private static Attributes buildAttributes(Map<String, Object> doc) {
        Object attrsRaw = doc.get("resource_attributes");
        if (attrsRaw instanceof Map<?, ?> map) {
            // Prefer the dedicated top-level ES fields for @type and @context when present,
            // so that keyword filtering against resource_attributes_type works correctly.
            String atType = doc.containsKey("resource_attributes_type")
                    ? (String) doc.get("resource_attributes_type")
                    : (String) map.get(BecknFields.AT_TYPE);
            String atContext = doc.containsKey("resource_attributes_context")
                    ? (String) doc.get("resource_attributes_context")
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

    @SuppressWarnings("unchecked")
    private static Constraint constraintFromMap(Map<String, Object> map) {
        Constraint c = new Constraint();
        c.setName(map.get("name") instanceof String s ? s : null);
        c.setValue(map.get("value"));
        map.forEach((k, v) -> {
            if (!"@type".equals(k) && !"name".equals(k) && !"value".equals(k))
                c.setAdditionalProperty(k, v);
        });
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Policy policyFromMap(Map<String, Object> map) {
        Policy p = new Policy();
        p.setName(map.get("name") instanceof String s ? s : null);
        map.forEach((k, v) -> {
            if (!"@type".equals(k) && !"name".equals(k) && !"descriptor".equals(k) && !"validity".equals(k))
                p.setAdditionalProperty(k, v);
        });
        return p;
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
