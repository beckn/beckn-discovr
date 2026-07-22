package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for manifest resolution from a full manifest URL (design doc §5.4 step 1). */
class ManifestResolverTest {

    private static final String MANIFEST = "https://prov.example/dedi.json";

    private CrawlerHttpClient http;
    private ManifestResolver resolver;

    @BeforeEach
    void setUp() {
        http = mock(CrawlerHttpClient.class);
        resolver = new ManifestResolver(http, new ObjectMapper());
    }

    @Test
    void resolve_returnsDomainAndFirstFileRef() throws Exception {
        String manifest = """
                {
                  "type": "dedi-manifest",
                  "domain": "prov.example",
                  "files": [
                    {"registry":"beckn-catalogs","url":"https://prov.example/dedi/idx.json","digest":"sha-256:abc","state":"live"}
                  ]
                }
                """;
        when(http.get(MANIFEST))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        List<ManifestResolver.Resolved> rs = resolver.resolve(MANIFEST);

        assertThat(rs).hasSize(1);
        ManifestResolver.Resolved r = rs.get(0);
        assertThat(r.domain()).isEqualTo("prov.example");
        assertThat(r.registry()).isEqualTo("beckn-catalogs");
        assertThat(r.indexUrl()).isEqualTo("https://prov.example/dedi/idx.json");
        assertThat(r.indexDigest()).isEqualTo("sha-256:abc");
        assertThat(r.state()).isEqualTo("live");
        assertThat(r.isLive()).isTrue();
    }

    @Test
    void resolve_returnsEveryFilesEntry() throws Exception {
        String manifest = """
                {
                  "type": "dedi-manifest",
                  "domain": "prov.example",
                  "files": [
                    {"registry":"beckn-catalogs","url":"https://prov.example/dedi/catalogs.json","digest":"sha-256:aaa","state":"live"},
                    {"registry":"beckn-offers","url":"https://prov.example/dedi/offers.json","digest":"sha-256:bbb","state":"live"}
                  ]
                }
                """;
        when(http.get(MANIFEST))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        List<ManifestResolver.Resolved> rs = resolver.resolve(MANIFEST);

        assertThat(rs).hasSize(2);
        assertThat(rs).extracting(ManifestResolver.Resolved::registry)
                .containsExactly("beckn-catalogs", "beckn-offers");
        assertThat(rs).extracting(ManifestResolver.Resolved::indexUrl)
                .containsExactly("https://prov.example/dedi/catalogs.json", "https://prov.example/dedi/offers.json");
    }

    @Test
    void resolve_carriesNonLiveState() throws Exception {
        String manifest = """
                {
                  "type": "dedi-manifest",
                  "domain": "prov.example",
                  "files": [
                    {"registry":"beckn-catalogs","url":"https://prov.example/dedi/idx.json","digest":"sha-256:abc","state":"retired"}
                  ]
                }
                """;
        when(http.get(MANIFEST))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        ManifestResolver.Resolved r = resolver.resolve(MANIFEST).get(0);

        assertThat(r.state()).isEqualTo("retired");
        assertThat(r.isLive()).isFalse();
    }

    @Test
    void resolve_throwsOnNon200() throws Exception {
        when(http.get(MANIFEST))
                .thenReturn(new CrawlerHttpClient.Response(404, new byte[0], null));

        assertThatThrownBy(() -> resolver.resolve(MANIFEST))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    void resolve_throwsWhenNoFilesEntry() throws Exception {
        String manifest = "{\"type\":\"dedi-manifest\",\"domain\":\"prov.example\",\"files\":[]}";
        when(http.get(MANIFEST))
                .thenReturn(new CrawlerHttpClient.Response(200, manifest.getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> resolver.resolve(MANIFEST))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no files");
    }
}
