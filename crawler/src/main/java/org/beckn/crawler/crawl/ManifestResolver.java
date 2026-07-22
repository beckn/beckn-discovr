package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.model.FeedModels.Manifest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Step 1 (design doc §5.4): derive {@code <base>/.well-known/dedi.json}, fetch it, and expose
 * the provider domain + the index URL/digest it vouches for. The manifest is tiny and fetched
 * every pass.
 */
@Component
public class ManifestResolver {

    /** What the manifest tells us: who the provider is and where/what the index should be. */
    public record Resolved(String domain, String name, String indexUrl, String indexDigest, String state) {
        /** True only when the index registry is live (DeDi state vocabulary). */
        public boolean isLive() {
            return "live".equalsIgnoreCase(state);
        }
    }

    private final CrawlerHttpClient http;
    private final ObjectMapper mapper;
    private final String wellKnownPath;

    public ManifestResolver(CrawlerHttpClient http, ObjectMapper mapper, CrawlerProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.wellKnownPath = props.wellKnownPath();
    }

    /** Builds the well-known URL from the provider base (no assumptions beyond the configured path). */
    public String manifestUrl(String providerBase) {
        String base = providerBase.endsWith("/") ? providerBase.substring(0, providerBase.length() - 1) : providerBase;
        String path = wellKnownPath.startsWith("/") ? wellKnownPath : "/" + wellKnownPath;
        return base + path;
    }

    /** Fetch + parse the manifest; return the domain + the first {@code files[]} pointer (the index). */
    public Resolved resolve(String providerBase) throws IOException, InterruptedException {
        String url = manifestUrl(providerBase);
        CrawlerHttpClient.Response resp = http.get(url);
        if (resp.status() != 200) {
            throw new IOException("manifest GET " + url + " returned HTTP " + resp.status());
        }
        Manifest m = mapper.readValue(resp.body(), Manifest.class);
        if (m.files() == null || m.files().isEmpty()) {
            throw new IOException("manifest " + url + " has no files[] entry");
        }
        Manifest.FileRef f = m.files().get(0); // POC: one registry (beckn-catalogs) per provider
        String name = m.name() != null && !m.name().isBlank() ? m.name() : m.domain();
        return new Resolved(m.domain(), name, f.url(), f.digest(), f.state());
    }
}
