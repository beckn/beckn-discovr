package org.beckn.discover.service.response;

import org.beckn.discover.common.BecknFields;
import org.beckn.discover.model.Attributes;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Descriptor;
import org.beckn.discover.model.Provider;
import org.beckn.discover.model.Resource;
import org.beckn.discover.util.DiscoveryServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provides catalog and item normalization, validation, and utility operations
 * used by the {@link CatalogPipeline}, assemblers, and
 * {@link ResponseProcessor}.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 * <li>Normalize items and catalogs (set type / context defaults).</li>
 * <li>Validate catalogs and items before response assembly.</li>
 * <li>Merge catalogs by provider (NLWeb-specific; called by
 * {@link org.beckn.discover.service.nlweb.NLWebAssembler}).</li>
 * <li>Offer deduplication, item / offer cross-filtering (called by
 * {@link CatalogPipeline}).</li>
 * <li>Schema context filtering (called by {@link CatalogPipeline}).</li>
 * </ul>
 *
 * <p>
 * PostgreSQL assemblers do not use {@link #mergeCatalogsByProvider} — SQL
 * groups by {@code catalog_id} at query time.
 * </p>
 */
@Component
public class CatalogProcessor {

    private static final Logger log = LoggerFactory.getLogger(CatalogProcessor.class);

    // ── Catalog normalization ────────────────────────────────────────────────

    /**
     * Normalizes a single catalog: validates required fields, processes all
     * items, and back-fills the {@code providerId} from item data when absent.
     *
     * @return the catalog (possibly mutated), or {@code null} if invalid
     */
    public Catalog processCatalog(Catalog catalog) {
        if (catalog == null)
            return null;

        if (DiscoveryServiceUtil.isBlank(catalog.getId())) {
            log.warn("catalog.process.skip reason=missing-id");
            return null;
        }

        if (catalog.getResources() != null) {
            catalog.setResources(
                    catalog.getResources().stream()
                            .map(this::processResource)
                            .filter(Objects::nonNull)
                            .toList());
        }

        // Back-fill providerId from the first resource that carries provider info
        if (catalog.getProviderId() == null && catalog.getResources() != null) {
            catalog.getResources().stream()
                    .map(Resource::getProvider)
                    .filter(Objects::nonNull)
                    .map(Provider::getId)
                    .filter(DiscoveryServiceUtil::isNotBlank)
                    .findFirst()
                    .ifPresent(catalog::setProviderId);
        }

        return catalog;
    }

    /**
     * Normalizes a single resource: validates required fields and sets type /
     * context defaults on attributes, provider, and descriptor.
     *
     * @return the resource (possibly mutated), or {@code null} if invalid
     */
    public Resource processResource(Resource resource) {
        if (resource == null)
            return null;

        if (DiscoveryServiceUtil.isBlank(resource.getId())) {
            log.warn("resource.process.skip reason=missing-id");
            return null;
        }

        if (resource.getResourceAttributes() != null)
            normalizeAttributes(resource.getResourceAttributes());
        if (resource.getProvider() != null)
            normalizeProvider(resource.getProvider());
        if (resource.getDescriptor() != null)
            normalizeDescriptor(resource.getDescriptor());

        return resource;
    }

    private void normalizeAttributes(Attributes attrs) {
        // @context and @type on resourceAttributes are required fields from the publisher.
        // We do not default them — if absent, they remain null (omitted from JSON via @JsonInclude).
    }

    private void normalizeProvider(Provider provider) {
        if (DiscoveryServiceUtil.isBlank(provider.getId())) {
            log.warn("provider.process.skip reason=missing-id");
            return;
        }
        if (provider.getDescriptor() != null)
            normalizeDescriptor(provider.getDescriptor());
    }

    private void normalizeDescriptor(Descriptor descriptor) {
        // No-op: Descriptor no longer has @type.
    }

    // ── Provider-based catalog merging (NLWeb only) ──────────────────────────

    /**
     * Merges catalogs that share the same provider into a single catalog.
     *
     * <p>
     * This is specific to the NLWeb / Elasticsearch response path where the
     * API may return multiple catalog objects for the same provider.
     * PostgreSQL groups by {@code catalog_id} in SQL, so this method is a
     * no-op for PostgreSQL-assembled catalogs.
     * </p>
     */
    public List<Catalog> mergeCatalogsByProvider(List<Catalog> catalogs) {
        if (catalogs == null || catalogs.isEmpty())
            return List.of();

        Map<String, Catalog> merged = new HashMap<>(catalogs.size());

        for (Catalog catalog : catalogs) {
            try {
                String key = providerKey(catalog);
                if (merged.containsKey(key)) {
                    mergeResources(merged.get(key), catalog);
                } else {
                    merged.put(key, catalog);
                }
            } catch (Exception e) {
                log.warn("catalog.merge.error id={} error={}", catalog.getId(), e.getMessage());
                merged.put(catalog.getId() + "_" + UUID.randomUUID(), catalog);
            }
        }

        List<Catalog> result = new ArrayList<>(merged.values());
        result.forEach(this::applyPostMergeDefaults);

        log.debug("catalog.merge.done input={} output={}", catalogs.size(), result.size());
        return result;
    }

    private String providerKey(Catalog catalog) {
        if (DiscoveryServiceUtil.isNotBlank(catalog.getProviderId()))
            return catalog.getProviderId();
        if (catalog.getResources() != null) {
            return catalog.getResources().stream()
                    .map(Resource::getProvider).filter(Objects::nonNull)
                    .map(Provider::getId).filter(DiscoveryServiceUtil::isNotBlank)
                    .findFirst()
                    .orElse(fallbackKey(catalog));
        }
        return fallbackKey(catalog);
    }

    private static String fallbackKey(Catalog catalog) {
        return DiscoveryServiceUtil.isNotBlank(catalog.getId())
                ? catalog.getId()
                : "unknown_" + UUID.randomUUID();
    }

    private void mergeResources(Catalog target, Catalog source) {
        if (source.getResources() == null || source.getResources().isEmpty())
            return;
        if (target.getResources() == null)
            target.setResources(new ArrayList<>());

        Set<String> existing = target.getResources().stream()
                .map(Resource::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        source.getResources().stream()
                .filter(r -> r.getId() != null && !existing.contains(r.getId()))
                .forEach(target.getResources()::add);

        if (target.getDescriptor() == null)
            target.setDescriptor(source.getDescriptor());
        if (target.getProviderId() == null)
            target.setProviderId(source.getProviderId());
    }

    private void applyPostMergeDefaults(Catalog catalog) {
        // No defaults to apply — all catalog metadata comes from publisher data.
    }

    // ── Offer operations ─────────────────────────────────────────────────────

    /**
     * Removes duplicate offers within a catalog by {@code id}.
     * No-op when the catalog has ≤1 offer.
     */
    public void deduplicateOffers(Catalog catalog) {
        if (catalog.getOffers() == null || catalog.getOffers().size() <= 1)
            return;

        List<Object> unique = catalog.getOffers().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        CatalogProcessor::offerId,
                        offer -> offer,
                        (existing, dup) -> existing,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
        catalog.setOffers(unique);
    }

    private static String offerId(Object offer) {
        if (offer instanceof Map<?, ?> map) {
            Object id = map.get(BecknFields.ID);
            if (id != null)
                return id.toString();
        }
        return String.valueOf(System.identityHashCode(offer));
    }

    /**
     * Keeps only items that are referenced by at least one offer.
     * No-op when no offers are present.
     */
    public void filterItemsByOfferReferences(Catalog catalog) {
        if (catalog.getOffers() == null || catalog.getOffers().isEmpty())
            return;
        if (catalog.getResources() == null || catalog.getResources().isEmpty())
            return;

        Set<String> referencedIds = catalog.getOffers().stream()
                .filter(Objects::nonNull)
                .flatMap(o -> offerItemIds(o).stream())
                .collect(Collectors.toSet());

        if (referencedIds.isEmpty())
            return;

        int before = catalog.getResources().size();
        catalog.setResources(catalog.getResources().stream()
                .filter(r -> referencedIds.contains(r.getId()))
                .toList());

        log.debug("catalog.offerFilter id={} resources.before={} resources.after={}",
                catalog.getId(), before, catalog.getResources().size());
    }

    /**
     * Removes offers whose referenced items do not exist in the catalog.
     * No-op when no offers are present.
     */
    public void filterOffersByItemIds(Catalog catalog) {
        if (catalog.getOffers() == null || catalog.getOffers().isEmpty())
            return;
        if (catalog.getResources() == null || catalog.getResources().isEmpty()) {
            catalog.setOffers(new ArrayList<>());
            return;
        }

        Set<String> resourceIds = catalog.getResources().stream()
                .map(Resource::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        catalog.setOffers(catalog.getOffers().stream()
                .filter(o -> offerItemIds(o).stream().anyMatch(resourceIds::contains))
                .toList());
    }

    /**
     * Extracts resource ID references from an offer map.
     * Offer-scoped resource references use {@code "resourceIds"}.
     */
    public static Set<String> offerItemIds(Object offer) {
        if (!(offer instanceof Map<?, ?> map))
            return Collections.emptySet();
        Object itemsObj = map.get("resourceIds");
        if (itemsObj instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toSet());
        }
        if (itemsObj instanceof String s)
            return Set.of(s);
        return Collections.emptySet();
    }

    // ── Schema context filtering ──────────────────────────────────────────────

    /**
     * Filters catalog items by schema context URL matching.
     * Called by {@link CatalogPipeline} after NLWeb / Elasticsearch assembly.
     * No-op when {@code schemaContextUrls} is empty.
     */
    public void filterCatalogsBySchemaContext(List<Catalog> catalogs, List<String> schemaContextUrls) {
        if (schemaContextUrls == null || schemaContextUrls.isEmpty())
            return;
        catalogs.forEach(catalog -> {
            if (catalog.getResources() == null)
                return;
            catalog.setResources(catalog.getResources().stream()
                    .filter(resource -> matchesSchema(resource, schemaContextUrls))
                    .toList());
        });
    }

    private boolean matchesSchema(Resource resource, List<String> schemaContextUrls) {
        if (resource.getResourceAttributes() == null || resource.getResourceAttributes().getContext() == null)
            return false;
        String itemCtx = resource.getResourceAttributes().getContext();
        String itemType = resource.getResourceAttributes().getType();

        for (String schemaUrl : schemaContextUrls) {
            if (DiscoveryServiceUtil.isBlank(schemaUrl))
                continue;
            String base = DiscoveryServiceUtil.extractBaseUrl(schemaUrl);
            String required = DiscoveryServiceUtil.extractFragment(schemaUrl);
            if (!itemCtx.equals(base))
                continue;
            if (DiscoveryServiceUtil.isBlank(required))
                return true;
            if (DiscoveryServiceUtil.isNotBlank(itemType)
                    && itemType.equals(required))
                return true;
        }
        return false;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /** Validates a catalog before including it in a response. */
    public boolean validateCatalog(Catalog catalog) {
        if (catalog == null) {
            log.warn("catalog.validate.fail reason=null");
            return false;
        }
        if (DiscoveryServiceUtil.isBlank(catalog.getId())) {
            log.warn("catalog.validate.fail reason=missing-id");
            return false;
        }
        if (catalog.getResources() == null || catalog.getResources().isEmpty()) {
            log.warn("catalog.validate.fail reason=no-resources id={}", catalog.getId());
            return false;
        }
        return catalog.getResources().stream().allMatch(this::validateResource);
    }

    /** Validates an individual resource. */
    public boolean validateResource(Resource resource) {
        if (resource == null) {
            log.warn("resource.validate.fail reason=null");
            return false;
        }
        if (DiscoveryServiceUtil.isBlank(resource.getId())) {
            log.warn("resource.validate.fail reason=missing-id");
            return false;
        }
        return true;
    }
}
