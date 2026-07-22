package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.util.DigestUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Fetch the index and parse it, returning its parsed form plus the digest of the bytes we fetched.
 *
 * <p>The index is polled on its own short cadence (per-minute), independently of the manifest
 * (weekly). Because the manifest's {@code files[].digest} is only a weekly snapshot, it can't be
 * used to gate or verify a per-minute index fetch — so change detection compares the freshly
 * computed index digest against the crawler's stored digest, and the caller decides.
 * We still check {@code publisher.domain} matches the manifest's provider. Full index integrity
 * (its own JWS signature verified against the publisher key) is deferred — §2 non-goals.
 */
@Component
public class IndexPoller {

    /** Raised when the index fails integrity checks — the caller logs feedback and skips the provider. */
    public static class IndexIntegrityException extends Exception {
        public IndexIntegrityException(String message) { super(message); }
    }

    /** The parsed index plus the sha-256 of the exact bytes fetched (the change signal). */
    public record Result(Index index, String digest) {}

    private final CrawlerHttpClient http;
    private final ObjectMapper mapper;

    public IndexPoller(CrawlerHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public Result fetch(ManifestResolver.Resolved manifest)
            throws IOException, InterruptedException, IndexIntegrityException {
        CrawlerHttpClient.Response resp = http.get(manifest.indexUrl());
        if (resp.status() != 200) {
            throw new IOException("index GET " + manifest.indexUrl() + " returned HTTP " + resp.status());
        }
        String digest = DigestUtil.sha256(resp.body());
        Index index = mapper.readValue(resp.body(), Index.class);
        String publisherDomain = index.publisher() == null ? null : index.publisher().domain();
        if (publisherDomain == null || !publisherDomain.equals(manifest.domain())) {
            throw new IndexIntegrityException("index publisher.domain '" + publisherDomain
                    + "' != manifest domain '" + manifest.domain() + "'");
        }
        return new Result(index, digest);
    }
}
