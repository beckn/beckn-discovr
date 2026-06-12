package org.beckn.discover.service.response;

import org.beckn.discover.model.Attributes;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CatalogProcessor#filterCatalogsBySchemaContext} multi-pair
 * pairing (F-14 / spec SC-45).
 *
 * <p>When a buyer scopes to two {@code context#type} pairs, each pair must match as a
 * unit: a resource may only qualify if BOTH its {@code @context} and {@code @type} come
 * from the SAME requested pair. A resource that mixes the context of one pair with the
 * type of another (cross-pair) must be excluded.</p>
 *
 * <p>The fix is fed the <b>raw</b> schemaContext URLs (with {@code #fragment}); previously
 * the pipeline passed the pre-split base-URL list, which dropped the fragment and made
 * type matching a silent no-op.</p>
 */
class CatalogProcessorSchemaPairingTest {

    private static final String GROCERY_CTX = "https://schema.beckn.io/Grocery";
    private static final String RETAIL_CTX  = "https://schema.beckn.io/Retail";

    private final CatalogProcessor processor = new CatalogProcessor();

    private static Resource resource(String id, String ctx, String type) {
        Resource r = new Resource();
        r.setId(id);
        r.setResourceAttributes(new Attributes(ctx, type));
        return r;
    }

    private static Catalog catalogWith(Resource... resources) {
        Catalog c = new Catalog();
        c.setId("cat-1");
        c.setResources(new ArrayList<>(List.of(resources)));
        return c;
    }

    @Test
    void multiPair_excludesCrossPairResources_keepsExactPairs() {
        Resource groceryGrocery = resource("R1", GROCERY_CTX, "GroceryResource"); // pair 1 ✓
        Resource retailRetail   = resource("R2", RETAIL_CTX,  "RetailResource");  // pair 2 ✓
        Resource groceryRetail  = resource("R3", GROCERY_CTX, "RetailResource");  // cross-pair ✗
        Resource retailGrocery  = resource("R4", RETAIL_CTX,  "GroceryResource"); // cross-pair ✗

        Catalog catalog = catalogWith(groceryGrocery, retailRetail, groceryRetail, retailGrocery);
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        // Raw schemaContext URLs preserving pairing
        List<String> rawUrls = List.of(
                GROCERY_CTX + "#GroceryResource",
                RETAIL_CTX + "#RetailResource");

        processor.filterCatalogsBySchemaContext(catalogs, rawUrls);

        assertThat(catalog.getResources())
                .extracting(Resource::getId)
                .as("cross-pair resources (Grocery-ctx+Retail-type, Retail-ctx+Grocery-type) must be excluded")
                .containsExactlyInAnyOrder("R1", "R2");
    }

    @Test
    void singlePair_matchesOnlyExactContextAndType() {
        Catalog catalog = catalogWith(
                resource("R1", GROCERY_CTX, "GroceryResource"),
                resource("R2", GROCERY_CTX, "RetailResource"));   // type mismatch ✗
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        processor.filterCatalogsBySchemaContext(catalogs, List.of(GROCERY_CTX + "#GroceryResource"));

        assertThat(catalog.getResources())
                .extracting(Resource::getId)
                .containsExactly("R1");
    }

    @Test
    void contextOnlyUrl_matchesAnyTypeForThatContext() {
        Catalog catalog = catalogWith(
                resource("R1", GROCERY_CTX, "GroceryResource"),
                resource("R2", GROCERY_CTX, "AnythingElse"),
                resource("R3", RETAIL_CTX,  "RetailResource"));   // different context ✗
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));

        processor.filterCatalogsBySchemaContext(catalogs, List.of(GROCERY_CTX));

        assertThat(catalog.getResources())
                .extracting(Resource::getId)
                .containsExactlyInAnyOrder("R1", "R2");
    }
}
