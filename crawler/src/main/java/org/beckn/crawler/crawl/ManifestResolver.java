package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ManifestResolver(CrawlerHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Fetch + parse the manifest at the given URL; one {@link Resolved} per {@code files[]} registry. */
    public List<Resolved> resolve(String manifestUrl) throws IOException, InterruptedException {
        CrawlerHttpClient.Response resp = http.get(manifestUrl);
        if (resp.status() != 200) {
            throw new IOException("manifest GET " + manifestUrl + " returned HTTP " + resp.status());
        }
        Manifest m = mapper.readValue(resp.body(), Manifest.class);
        if (m.files() == null || m.files().isEmpty()) {
            throw new IOException("manifest " + manifestUrl + " has no files[] entry");
        }
        String name = m.name() != null && !m.name().isBlank() ? m.name() : m.domain();
        List<Resolved> resolved = new ArrayList<>();
        for (Manifest.FileRef f : m.files()) {
            resolved.add(new Resolved(m.domain(), name, f.registry(), f.url(), f.digest(), f.state()));
        }
        return resolved;
    }
}
