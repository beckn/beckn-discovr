package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.model.FeedModels.Manifest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 (design doc §5.4): derive {@code <base>/.well-known/dedi.json}, fetch it, and expose the
 * provider identity plus <b>every</b> registry it advertises in {@code files[]}. The manifest is
 * tiny and re-read on the long (manifest-refresh) cadence.
 *
 * <p>A provider may list several registries (each a {@code files[]} entry with its own url/digest/
 * state); each is crawled as an independent index, keyed by its own url in the state store.
 */
@Component
public class ManifestResolver {

    /** One registry the manifest advertises: who the provider is + where/what that index should be. */
    public record Resolved(String domain, String name, String registry,
                           String indexUrl, String indexDigest, String state) {
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

    /** Fetch + parse the manifest; return one {@link Resolved} per {@code files[]} entry (all registries). */
    public List<Resolved> resolve(String providerBase) throws IOException, InterruptedException {
        String url = manifestUrl(providerBase);
        CrawlerHttpClient.Response resp = http.get(url);
        if (resp.status() != 200) {
            throw new IOException("manifest GET " + url + " returned HTTP " + resp.status());
        }
        Manifest m = mapper.readValue(resp.body(), Manifest.class);
        if (m.files() == null || m.files().isEmpty()) {
            throw new IOException("manifest " + url + " has no files[] entry");
        }
        String name = m.name() != null && !m.name().isBlank() ? m.name() : m.domain();
        List<Resolved> resolved = new ArrayList<>();
        for (Manifest.FileRef f : m.files()) {
            resolved.add(new Resolved(m.domain(), name, f.registry(), f.url(), f.digest(), f.state()));
        }
        return resolved;
    }
}
