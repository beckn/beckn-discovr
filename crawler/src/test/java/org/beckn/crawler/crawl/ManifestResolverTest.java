package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.support.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for manifest URL derivation + resolution (design doc §5.4 step 1). */
class ManifestResolverTest {

    private CrawlerHttpClient http;
    private ManifestResolver resolver;

    @BeforeEach
    void setUp() {
        http = mock(CrawlerHttpClient.class);
        resolver = new ManifestResolver(http, new ObjectMapper(), TestConfigs.props("http://push"));
    }

    @Test
    void manifestUrl_appendsWellKnownPath() {
        assertThat(resolver.manifestUrl("https://prov.example"))
                .isEqualTo("https://prov.example/.well-known/dedi.json");
    }

    @Test
    void manifestUrl_handlesTrailingSlashOnBase() {
        assertThat(resolver.manifestUrl("https://prov.example/"))
                .isEqualTo("https://prov.example/.well-known/dedi.json");
    }

    @Test
    void resolve_returnsDomainAndFirstFileRef() throws Exception {
        String manifest = """
                {
                  "type": "dedi-manifest",
                  "domain": "prov.example",
                  "files": [
                    {"registry":"beckn-catalogs","url":"https://prov.example/dedi/idx.json","digest":"sha-256:abc","state":"ACTIVE"}
                  ]
                }
                """;
        when(http.get("https://prov.example/.well-known/dedi.json"))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        ManifestResolver.Resolved r = resolver.resolve("https://prov.example");

        assertThat(r.domain()).isEqualTo("prov.example");
        assertThat(r.indexUrl()).isEqualTo("https://prov.example/dedi/idx.json");
        assertThat(r.indexDigest()).isEqualTo("sha-256:abc");
    }

    @Test
    void resolve_throwsOnNon200() throws Exception {
        when(http.get("https://prov.example/.well-known/dedi.json"))
                .thenReturn(new CrawlerHttpClient.Response(404, new byte[0], null));

        assertThatThrownBy(() -> resolver.resolve("https://prov.example"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    void resolve_throwsWhenNoFilesEntry() throws Exception {
        String manifest = "{\"type\":\"dedi-manifest\",\"domain\":\"prov.example\",\"files\":[]}";
        when(http.get("https://prov.example/.well-known/dedi.json"))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> resolver.resolve("https://prov.example"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no files");
    }
}
