package org.beckn.catalogpublish.controller;

import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Secure downloader for the (untrusted) on_pull {@code downloadManifest.url}. Owns the whole
 * defence-in-depth stack for pulling a catalog asset off the network:
 * <ul>
 *   <li>SSRF guard ({@link #validateDownloadUrl}) — scheme allow-list + private/loopback rejection,
 *       run ONCE before any network call (a permanent failure — never retried);</li>
 *   <li>an {@link HttpClient} that NEVER follows redirects (closes the SSRF-via-redirect bypass) with
 *       the same 10s connect timeout as before;</li>
 *   <li>a streaming download with a HARD size cap ({@code pullMaxDownloadBytes}) that aborts mid-stream
 *       without buffering the whole body (gzip-bomb / OOM guard, Content-Length not trusted);</li>
 *   <li>bounded retry (Spring {@link Retryable}) for TRANSIENT failures only (HTTP 5xx / network) —
 *       4xx / SSRF / size-cap failures propagate immediately without retry.</li>
 * </ul>
 *
 * <p>Extracted from {@code CatalogPullCallbackService} so the retry is driven by the Spring proxy
 * (which only intercepts cross-bean calls — a self-invocation would NOT retry) and so the callback
 * service stays under the size limit.</p>
 */
@Component
public class SecureCatalogDownloader {

    private static final Logger log = LoggerFactory.getLogger(SecureCatalogDownloader.class);

    /**
     * Tiny hard cap (64 KiB) on how much of a non-200 response body we read-and-discard when draining
     * the stream so the connection can be pooled. The error body is irrelevant; capping it prevents a
     * malicious retried 5xx with an unbounded body from forcing repeated unbounded buffering (OOM).
     */
    private static final long ERROR_DRAIN_CAP_BYTES = 64L * 1024L;

    /**
     * Download or decompressed output exceeded the configured hard cap (gzip-bomb / OOM guard).
     * Subtypes {@link IOException} so the streaming download/decompress throws-contracts and the
     * "discard on failure" behavior are unchanged; it carries the observed byte count and the limit
     * so the caller can log them and record the distinct {@code size_exceeded} reason.
     */
    static final class SizeExceededException extends IOException {
        private final long bytes;
        private final long limit;

        SizeExceededException(String what, long bytes, long limit) {
            super(what + " exceeded cap: " + bytes + " > " + limit + " bytes");
            this.bytes = bytes;
            this.limit = limit;
        }

        long bytes() {
            return bytes;
        }

        long limit() {
            return limit;
        }
    }

    /**
     * Marks a TRANSIENT download failure (HTTP 5xx / network / timeout) that is worth retrying.
     * Extends {@link IOException} so that, once retries are exhausted, the caller's
     * {@code catch (IOException)} classifies it as {@code download_http_error}. Permanent failures
     * (4xx, SSRF, checksum, {@link SizeExceededException}) are deliberately NOT this type, so they
     * propagate immediately without retry.
     */
    static final class RetryableDownloadException extends IOException {
        RetryableDownloadException(String message) {
            super(message);
        }

        RetryableDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final CatalogPublishMetrics metrics;
    private final AppProperties props;
    private final HttpClient httpClient;

    public SecureCatalogDownloader(CatalogPublishMetrics metrics, AppProperties props) {
        this.metrics = metrics;
        this.props = props;
        // Build with the DEFAULT redirect policy (NEVER follow redirects): a validated URL cannot be
        // bounced to an internal target after validation. Same 10s connect timeout as before.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Downloads catalog bytes from a URL, applying the SSRF guard once and retrying only TRANSIENT
     * failures (bounded attempts/backoff via Spring {@link Retryable}).
     *
     * @param downloadUrl the HTTP(S) URL to download from
     * @return the raw downloaded (still-compressed) bytes
     * @throws IllegalArgumentException if the SSRF guard rejects the URL (permanent — not retried)
     * @throws SizeExceededException if the download exceeds the hard cap (permanent — not retried)
     * @throws IOException on a non-200 4xx (permanent) or, after retries are exhausted, the last
     *         transient failure
     * @throws Exception if the download attempt is interrupted
     */
    @Retryable(retryFor = RetryableDownloadException.class,
            maxAttemptsExpression = "${app.catalog.pull-download-max-attempts:3}",
            backoff = @Backoff(delayExpression = "${app.catalog.pull-download-retry-backoff-ms:1000}",
                    multiplier = 2, maxDelay = 30000))
    public byte[] download(String downloadUrl) throws Exception {
        // On a retry attempt (retryCount > 0 means a prior attempt already failed transiently and this
        // is the re-attempt), record the retry metric ONCE per re-attempt — matching the old loop, which
        // counted a retry only when a further attempt actually followed a transient failure. The final
        // exhausting failure is NOT counted here (it recovers), so N attempts => N-1 retry recordings.
        RetryContext ctx = RetrySynchronizationManager.getContext();
        if (ctx != null && ctx.getRetryCount() > 0) {
            log.warn("event={} url={} attempt={} reason=retry", LogEvent.ON_PULL_DOWNLOAD_RETRY,
                    redactUrl(downloadUrl), ctx.getRetryCount() + 1);
            metrics.recordOnPullDownloadRetry();
        }
        // SSRF guard: validate the (untrusted) manifest URL ONCE before any network call. It throws
        // IllegalArgumentException (not RetryableDownloadException), so a rejection is never retried.
        validateDownloadUrl(downloadUrl);
        return attemptDownload(downloadUrl);
    }

    /**
     * Recovery entry point for Spring Retry. Two cases reach here:
     * <ul>
     *   <li>retries EXHAUSTED on a {@link RetryableDownloadException} — rethrow the last transient error
     *       so the caller sees the REAL cause exactly as the old loop's final throw did (its
     *       {@code catch (IOException)} classifies it as {@code download_http_error});</li>
     *   <li>a NON-retryable {@link IOException} (e.g. a 4xx) thrown from the very first attempt — Spring
     *       routes it here too; rethrow it verbatim so the 4xx propagates immediately, unretried.</li>
     * </ul>
     * Declared with the {@link IOException} supertype so a single {@code @Recover} matches BOTH the
     * retryable subtype (after exhaustion) and a directly-thrown non-retryable 4xx, preserving the
     * original exception's identity/message in every case.
     */
    @Recover
    public byte[] recoverDownload(IOException lastError, String downloadUrl) throws IOException {
        log.warn("event={} url={} reason=download-terminal error={}",
                LogEvent.ON_PULL_DOWNLOAD_RETRY, redactUrl(downloadUrl),
                ErrorSanitizer.sanitize(lastError.getMessage()));
        throw lastError;
    }

    /** A single download attempt. Throws {@link RetryableDownloadException} for transient failures. */
    private byte[] attemptDownload(String downloadUrl) throws Exception {
        long maxDownloadBytes = props.catalog().pullMaxDownloadBytes();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response;
        try {
            // Stream the body (ofInputStream) and read into a growable buffer with a HARD cap — abort as
            // soon as bytes read exceed maxDownloadBytes, WITHOUT buffering the whole body first. Do NOT
            // trust Content-Length alone (a lying header could still stream an unbounded body).
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException networkErr) {
            // Connection reset / timeout / DNS blip → transient, retry. Redact the (possibly signed) URL —
            // this message is later logged via ErrorSanitizer, which does not strip signed-URL signatures.
            throw new RetryableDownloadException(
                    "download IO error from " + redactUrl(downloadUrl) + ": " + networkErr.getMessage(), networkErr);
        } catch (InterruptedException ie) {
            // HttpClient.send() is interruptible: restore the flag and propagate so shutdown/cancellation
            // is not silently swallowed as a generic processing_error.
            Thread.currentThread().interrupt();
            throw ie;
        }
        if (response.statusCode() != 200) {
            // Read+discard into a small fixed buffer up to a tiny cap, then close, so the connection can
            // be returned to the pool WITHOUT unbounded buffering. A malicious 503 with an unbounded body
            // is retried up to pullDownloadMaxAttempts, so an uncapped readAllBytes() would let it force
            // repeated unbounded buffering. The body is irrelevant on the error path.
            try (java.io.InputStream body = response.body()) {
                byte[] discard = new byte[8192];
                long drained = 0;
                while (drained < ERROR_DRAIN_CAP_BYTES && body.read(discard) > 0) {
                    drained += discard.length;
                }
            } catch (IOException ignored) {
                // best-effort drain; the non-200 error below is what matters
            }
            if (response.statusCode() >= 500) {
                // 5xx → transient (subscriber/object-store issue), retry. Redact the (possibly signed) URL
                // in the message — it is later logged via ErrorSanitizer, which does not strip signatures.
                throw new RetryableDownloadException(
                        "Failed to download catalog file from " + redactUrl(downloadUrl) + " - HTTP " + response.statusCode());
            }
            // 4xx → permanent (bad/expired URL), do NOT retry. Redact the URL for the same reason.
            throw new IOException("Failed to download catalog file from " + redactUrl(downloadUrl) + " - HTTP " + response.statusCode());
        }
        try (java.io.InputStream body = response.body();
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(chunk)) > 0) {
                total += read;
                if (total > maxDownloadBytes) {
                    throw new SizeExceededException("download", total, maxDownloadBytes);
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * SSRF guard for the (untrusted) {@code downloadManifest.url}. Allows only http/https with a
     * resolvable, non-private host. Fails closed: an unresolvable host is rejected rather than fetched.
     *
     * @param url the manifest download URL
     * @throws IllegalArgumentException if the URL is malformed, non-http(s), or resolves to a
     *         loopback / link-local / site-local / any-local / multicast address
     */
    void validateDownloadUrl(String url) {
        // Secure-by-default gate. When app.catalog.pull-ssrf-check-enabled is explicitly false
        // (local/dev), skip the guard and WARN that it is disabled. Absent/true => enforce as today.
        if (Boolean.FALSE.equals(props.catalog().pullSsrfCheckEnabled())) {
            log.warn("event={} reason=ssrf-guard-disabled url={}", LogEvent.ON_PULL_SSRF_DISABLED, redactUrl(url));
            return;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed download URL: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new IllegalArgumentException("Invalid download URL scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Invalid download URL: no host");
        }

        try {
            // DNS-rebinding TOCTOU fix (part a): resolve ALL A/AAAA records and reject if ANY is
            // private/local. Checking only the first address (getByName) let a host with both a public
            // and a private record slip through — the fetch-time re-resolution could then pick the
            // private one. getAllByName forces every advertised address to be safe.
            //
            // DNS-rebinding TOCTOU fix (part b): DnsCacheHardeningConfig sets a POSITIVE DNS cache TTL
            // at startup, so the JVM caches THIS validated positive resolution for the TTL window. When
            // java.net.http.HttpClient re-resolves the host at fetch time it reuses the SAME cached
            // (validated) IPs, so a hostile DNS server that returns TTL=0 and flips to a private IP
            // after validation cannot take effect within the window. Full per-connection IP pinning
            // (resolve-once + connect-to-that-IP) needs Java 18's InetAddressResolverProvider; on
            // Java 17 the positive-TTL cache + all-address check is the strongest available closure.
            // The SSRF-via-redirect bypass is separately closed: this HttpClient NEVER follows redirects.
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException("Download URL points to private/loopback address: " + host);
                }
            }
        } catch (java.net.UnknownHostException e) {
            // Fail closed: an unresolvable host cannot be verified safe.
            throw new IllegalArgumentException("Download URL host could not be resolved: " + host, e);
        }
    }

    /**
     * Redacts a download URL for logging: returns {@code scheme://host/path} only, stripping the query
     * string. GCS V4 signed URLs carry {@code X-Goog-Signature} (a bearer capability) in the query, so
     * logging the raw URL would leak a credential. Best-effort — on any parse failure returns a fixed
     * placeholder rather than risking leaking the raw (possibly signed) URL. Does NOT change the URL
     * actually fetched.
     */
    static String redactUrl(String url) {
        if (url == null) {
            return "(none)";
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (scheme == null || host == null) {
                return "(redacted)";
            }
            return scheme + "://" + host + (path == null ? "" : path);
        } catch (RuntimeException e) {
            return "(redacted)";
        }
    }
}
