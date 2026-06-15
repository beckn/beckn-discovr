package org.beckn.discover.service.postgresql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-completeness guard for {@link PostgreSQLAssembler} — the assembler behind
 * every PostgreSQL-backed route: J (Path B), G (Path C, PG), J+G (Path A), and the
 * chain step 2 (J+T / J+G+T).
 *
 * <p>Regression context (fabric-support issue #63): a J+G discover returned catalogs
 * that were missing {@code bppId} / {@code bppUri} / {@code isActive}. The root cause
 * was publish-side (some catalogs were indexed without those fields), but these tests
 * lock in the discover-side contract: <b>whatever catalog-level identity fields are
 * present in the stored payload's {@code catalogs[0]} must survive assembly</b>, so a
 * future change to the query path or assembler cannot silently drop them. Mirrors the
 * equivalent ES-path coverage in {@code EsSearchAssemblerTest}.</p>
 */
class PostgreSQLAssemblerTest {

    private PostgreSQLAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new PostgreSQLAssembler(new ObjectMapper());
    }

    private static QueryRequest request() {
        return new QueryRequest("tx-1", "msg-1", null, List.of(), null, List.of(), List.of());
    }

    private static Map<String, Object> row(String itemId, String catalogId, String payloadJson) {
        return Map.of("id", itemId, "catalog_id", catalogId, "resource_payload", payloadJson);
    }

    @Test
    @DisplayName("catalog with bppId/bppUri/isActive in payload → all three survive assembly (all PG routes)")
    void catalogIdentityFields_arePreserved() {
        String payload = """
                {"catalogs":[{
                  "id":"cat-1",
                  "bppId":"bpp.example.com",
                  "bppUri":"https://bpp.example.com/bpp/receiver",
                  "isActive":true,
                  "descriptor":{"name":"Soil Testing Services"},
                  "provider":{"id":"prov-1","descriptor":{"name":"Prov"}},
                  "resources":[{"id":"item-1","descriptor":{"name":"Soil Test"}}],
                  "offers":[]
                }]}""";

        List<Catalog> catalogs = assembler.assemble(List.of(row("item-1", "cat-1", payload)), request());

        assertThat(catalogs).hasSize(1);
        Catalog c = catalogs.get(0);
        assertThat(c.getBppId()).isEqualTo("bpp.example.com");
        assertThat(c.getBppUri()).isEqualTo("https://bpp.example.com/bpp/receiver");
        assertThat(c.getIsActive()).isTrue();
        assertThat(c.getResources()).hasSize(1);
    }

    @Test
    @DisplayName("isActive=false is preserved (not dropped as a falsy value)")
    void isActiveFalse_isPreserved() {
        String payload = """
                {"catalogs":[{
                  "id":"cat-1","bppId":"bpp.example.com","bppUri":"https://bpp.example.com/r","isActive":false,
                  "descriptor":{"name":"Cat"},
                  "resources":[{"id":"item-1","descriptor":{"name":"R"}}],"offers":[]
                }]}""";

        List<Catalog> catalogs = assembler.assemble(List.of(row("item-1", "cat-1", payload)), request());

        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).getIsActive()).isFalse();
    }

    @Test
    @DisplayName("catalog indexed WITHOUT bppId/bppUri → fields are null (documents issue #63 publish-gap; discover returns only what is indexed)")
    void catalogWithoutBppFields_yieldsNulls_notFabricated() {
        // This is the exact shape of the offer-resolution "-temp" catalogs in issue #63:
        // isActive present, bppId/bppUri absent from storage.
        String payload = """
                {"catalogs":[{
                  "id":"cat-temp",
                  "isActive":true,
                  "descriptor":{"name":"Agricultural Procurement Services"},
                  "provider":{"id":"PROC-212"},
                  "resources":[{"id":"item-1","descriptor":{"name":"Onion"}}],
                  "offers":[]
                }]}""";

        List<Catalog> catalogs = assembler.assemble(List.of(row("item-1", "cat-temp", payload)), request());

        assertThat(catalogs).hasSize(1);
        Catalog c = catalogs.get(0);
        // isActive is in the data → present; bppId/bppUri are not in the data → null
        // (omitted from the response by @JsonInclude(NON_NULL)). Discover must NOT
        // fabricate them — the fix for #63 belongs in the publish/indexing layer.
        assertThat(c.getIsActive()).isTrue();
        assertThat(c.getBppId()).isNull();
        assertThat(c.getBppUri()).isNull();
    }
}
