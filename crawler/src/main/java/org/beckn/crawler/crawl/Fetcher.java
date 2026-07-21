package org.beckn.crawler.crawl;

import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.util.DigestUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Step 5 (design doc §5.4): GET a catalog part and verify its bytes against the digest the
 * index published. A mismatch is a hard reject — never index unverified bytes (§5.7).
 */
@Component
public class Fetcher {

    /** Raised when a part's bytes don't match its announced digest. */
    public static class DigestMismatchException extends Exception {
        public DigestMismatchException(String message) { super(message); }
    }

    private final CrawlerHttpClient http;

    public Fetcher(CrawlerHttpClient http) {
        this.http = http;
    }

    /** Returns the verified raw bytes of the part (safe to push). */
    public byte[] fetchVerified(String partUrl, String expectedDigest)
            throws IOException, InterruptedException, DigestMismatchException {
        CrawlerHttpClient.Response resp = http.get(partUrl);
        if (resp.status() != 200) {
            throw new IOException("part GET " + partUrl + " returned HTTP " + resp.status());
        }
        if (!DigestUtil.matches(resp.body(), expectedDigest)) {
            throw new DigestMismatchException("expected " + expectedDigest
                    + " got " + DigestUtil.sha256(resp.body()));
        }
        return resp.body();
    }
}
