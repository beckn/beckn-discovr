package org.beckn.discover.service.response;

import org.beckn.discover.model.Attributes;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-context pairing guard for the post-query prune
 * ({@link CatalogProcessor#filterCatalogsBySchemaContext}) — the layer that
 * enforces schema filtering for engine paths whose {@code appliesSchemaFilter()}
 * is false (e.g. NLWeb text search), where there is no SQL/ES pre-filter.
 *
 * <p>Regression: spec SC-45 / F-14. The prune must honour {@code <ctx>#<type>}
 * pairing — a resource with one pair's context and another pair's type must NOT
 * survive. This is fed the RAW {@code url#type} entries by {@code CatalogPipeline}
 * (not the pre-split base-URL list, which would strip the fragment and degrade to
 * context-only matching, re-opening the cross-pair leak).</p>
 */
class CatalogProcessorSchemaPairingTest {

    private static final String GROCERY = "https://schema.org/Grocery";
    private static final String RETAIL  = "https://schema.org/Retail";

    private CatalogProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CatalogProcessor();
    }

    private static Resource resource(String id, String ctx, String type) {
        Attributes attrs = new Attributes();
        attrs.setContext(ctx);
        attrs.setType(type);
        Resource r = new Resource();
        r.setId(id);
        r.setResourceAttributes(attrs);
        return r;
    }

    private static Catalog catalogWith(Resource... resources) {
        Catalog c = new Catalog();
        c.setId("cat-1");
        c.setResources(new ArrayList<>(List.of(resources)));
        return c;
    }

    @Test
    @DisplayName("cross-pair resource (Grocery-ctx + Retail-type) is pruned; matching pairs survive")
    void crossPairResource_isPruned() {
        Resource grocery   = resource("r-grocery",   GROCERY, "GroceryResource"); // matches pair 1
        Resource retail    = resource("r-retail",    RETAIL,  "RetailResource");  // matches pair 2
        Resource crossPair = resource("r-cross",     GROCERY, "RetailResource");  // matches NEITHER pair
        List<Catalog> catalogs = new ArrayList<>(List.of(catalogWith(grocery, retail, crossPair)));

        processor.filterCatalogsBySchemaContext(catalogs,
                List.of(GROCERY + "#GroceryResource", RETAIL + "#RetailResource"));

        assertThat(catalogs.get(0).getResources())
                .extracting(Resource::getId)
                .containsExactlyInAnyOrder("r-grocery", "r-retail")
                .doesNotContain("r-cross");
    }

    @Test
    @DisplayName("context-only entry (no #type) matches any type under that context")
    void contextOnly_matchesAnyType() {
        Resource a = resource("r-a", GROCERY, "GroceryResource");
        Resource b = resource("r-b", GROCERY, "SomethingElse");
        Resource c = resource("r-c", RETAIL,  "RetailResource"); // different context → excluded
        List<Catalog> catalogs = new ArrayList<>(List.of(catalogWith(a, b, c)));

        processor.filterCatalogsBySchemaContext(catalogs, List.of(GROCERY));

        assertThat(catalogs.get(0).getResources())
                .extracting(Resource::getId)
                .containsExactlyInAnyOrder("r-a", "r-b");
    }

    @Test
    @DisplayName("single pair keeps only the exact (context, type) match")
    void singlePair_exactMatchOnly() {
        Resource match    = resource("r-match",    GROCERY, "GroceryResource");
        Resource wrongType = resource("r-wrongtype", GROCERY, "OtherType");
        List<Catalog> catalogs = new ArrayList<>(List.of(catalogWith(match, wrongType)));

        processor.filterCatalogsBySchemaContext(catalogs, List.of(GROCERY + "#GroceryResource"));

        assertThat(catalogs.get(0).getResources())
                .extracting(Resource::getId)
                .containsExactly("r-match");
    }
}
