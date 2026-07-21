package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.util.DigestUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Step 3 (design doc §5.4): fetch the index, verify its bytes against the manifest digest and
 * that its {@code publisher.domain} matches the manifest domain, then parse it.
 * (Proof/JWS verification is deferred — §2 non-goals.)
 */
@Component
public class IndexPoller {

    /** Raised when the index fails integrity checks — the caller logs feedback and skips the provider. */
    public static class IndexIntegrityException extends Exception {
        public IndexIntegrityException(String message) { super(message); }
    }

    private final CrawlerHttpClient http;
    private final ObjectMapper mapper;

    public IndexPoller(CrawlerHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public Index fetchAndVerify(ManifestResolver.Resolved manifest)
            throws IOException, InterruptedException, IndexIntegrityException {
        CrawlerHttpClient.Response resp = http.get(manifest.indexUrl());
        if (resp.status() != 200) {
            throw new IOException("index GET " + manifest.indexUrl() + " returned HTTP " + resp.status());
        }
        // Integrity anchor: the index bytes must hash to the digest the manifest published.
        if (!DigestUtil.matches(resp.body(), manifest.indexDigest())) {
            throw new IndexIntegrityException("index digest mismatch: expected " + manifest.indexDigest()
                    + " got " + DigestUtil.sha256(resp.body()));
        }
        Index index = mapper.readValue(resp.body(), Index.class);
        String publisherDomain = index.publisher() == null ? null : index.publisher().domain();
        if (publisherDomain == null || !publisherDomain.equals(manifest.domain())) {
            throw new IndexIntegrityException("index publisher.domain '" + publisherDomain
                    + "' != manifest domain '" + manifest.domain() + "'");
        }
        return index;
    }
}
