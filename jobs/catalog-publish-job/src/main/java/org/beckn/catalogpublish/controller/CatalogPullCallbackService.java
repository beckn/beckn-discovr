package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.logging.MdcField;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.zip.GZIPInputStream;

/**
 * Service responsible for processing incoming Beckn catalog {@code on_pull} callbacks asynchronously.
 * Supports processing inline catalogs and downloading, decompressing, and verifying catalog assets
 * referenced in a {@code downloadManifest}.
 *
 * <p>The secure download itself (SSRF guard + bounded {@link org.springframework.retry.annotation.Retryable}
 * retry + size cap + no-redirect HttpClient) lives in {@link SecureCatalogDownloader}, injected as a
 * separate bean so Spring's retry proxy actually intercepts the call (a self-invocation would not).</p>
 */
@Service
public class CatalogPullCallbackService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPullCallbackService.class);

    /**
     * Marker thrown from {@link #processDownloadManifest} after a specific download/verify/decompress
     * failure has ALREADY been logged and counted with its own bounded reason tag. The outer catch in
     * {@link #processPullCallbackAsynchronously} recognizes it and does NOT re-record
     * {@code processing_error}, avoiding double counting. Behavior is unchanged: the callback is still
     * discarded (FAILED-equivalent outcome), exactly as before when the underlying exception bubbled up.
     */
    private static final class AlreadyRecordedFailure extends RuntimeException {
        AlreadyRecordedFailure(Throwable cause) {
            super(cause);
        }
    }

    /**
     * SHA-256 checksum mismatch. Subtypes {@link IOException} so {@code verifySha256Checksum}'s
     * throws-contract and the "discard on failure" behavior are unchanged; it merely carries the
     * expected/actual hashes so the caller can log them under a distinct bounded reason.
     */
    private static final class ChecksumMismatchException extends IOException {
        private final String expected;
        private final String actual;

        ChecksumMismatchException(String expected, String actual) {
            super("SHA-256 checksum verification failed. Expected: " + expected + ", Actual: " + actual);
            this.expected = expected;
            this.actual = actual;
        }

        String expected() {
            return expected;
        }

        String actual() {
            return actual;
        }
    }

    private final CatalogPushService pushService;
    private final ObjectMapper objectMapper;
    private final CatalogPublishMetrics metrics;
    private final AppProperties props;
    private final SecureCatalogDownloader downloader;

    /**
     * Constructs the callback processing service.
     *
     * @param pushService the service used to enqueue transformed catalogs
     * @param objectMapper the JSON object mapper
     * @param metrics publish/on_pull metrics recorder
     * @param props application configuration (gates the on_pull decompress cap)
     * @param downloader the secure downloader bean (SSRF guard + bounded @Retryable retry + size cap);
     *        a SEPARATE bean so Spring's retry proxy intercepts the download call
     */
    public CatalogPullCallbackService(CatalogPushService pushService, ObjectMapper objectMapper,
            CatalogPublishMetrics metrics, AppProperties props, SecureCatalogDownloader downloader) {
        this.pushService = pushService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.props = props;
        this.downloader = downloader;
    }

    /**
     * Asynchronously processes the raw string payload of a Beckn {@code on_pull} callback.
     * Evaluates the callback status, extracts/downloads catalogs, and pushes them to the ingestion pipeline.
     *
     * @param rawCallbackPayload the raw JSON request body received at the on_pull endpoint
     */
    @Async("onPullExecutor")
    public void processPullCallbackAsynchronously(String rawCallbackPayload) {
        try {
            JsonNode callbackPayloadRoot = objectMapper.readTree(rawCallbackPayload);
            JsonNode becknContext = callbackPayloadRoot.path(BecknFields.CONTEXT);
            JsonNode becknMessage = callbackPayloadRoot.path(BecknFields.MESSAGE);

            // Carry the ORIGINAL correlation IDs from the CS callback context onto every log
            // line for this callback (LogstashEncoder auto-includes MDC). These are the IDs the
            // CS sent — never a locally generated/substitute value. Runs on a pooled thread that
            // inherits no MDC, so we set it here and clear it in finally.
            populateCallbackMdc(becknContext);

            String callbackStatus = becknMessage.path("status").asText("");
            if ("FAILED".equalsIgnoreCase(callbackStatus)) {
                JsonNode callbackError = becknMessage.path("error");
                log.warn("event={} status=FAILED errorCode={} errorMessage={}",
                        LogEvent.ON_PULL_FAILED,
                        callbackError.path("code").asText(""),
                        callbackError.path("message").asText(""));
                metrics.recordOnPullFailed("status_failed");
                return;
            }

            JsonNode inlineCatalogsArray = becknMessage.path(BecknFields.CATALOGS);
            JsonNode downloadManifestNode = becknMessage.path("downloadManifest");

            if (inlineCatalogsArray.isArray() && !inlineCatalogsArray.isEmpty()) {
                metrics.recordOnPullReceived("inline");
                recordPaginationIfPresent("inline", becknMessage);
                log.info("event={} mode=INLINE catalogsReturned={}",
                        LogEvent.ON_PULL_MODE_SELECTED, inlineCatalogsArray.size());
                enqueueAndObserve(becknContext, inlineCatalogsArray, "inline");
                metrics.recordOnPullProcessed("inline");
            } else if (!downloadManifestNode.isMissingNode() && !downloadManifestNode.isNull()) {
                recordPaginationIfPresent("download", becknMessage);
                processDownloadManifest(becknContext, downloadManifestNode);
            } else {
                log.warn("event={} reason=empty-callback", LogEvent.ON_PULL_FAILED);
                metrics.recordOnPullFailed("empty_callback");
            }

        } catch (AlreadyRecordedFailure e) {
            // Specific download/verify/decompress failure already logged + counted with its own
            // bounded reason in processDownloadManifest. Do NOT re-record processing_error
            // (no double counting). Callback is still discarded — behavior unchanged.
        } catch (Exception e) {
            log.error("event={} error={}",
                    LogEvent.ON_PULL_FAILED,
                    ErrorSanitizer.sanitize(e.getMessage()),
                    e);
            metrics.recordOnPullFailed("processing_error");
        } finally {
            // Pool thread: clear MDC so this callback's correlation IDs never leak into the
            // next task scheduled on the same thread.
            MDC.clear();
        }
    }

    /**
     * Processes a COMPLETED on_pull callback delivered via {@code downloadManifest}.
     *
     * <p>Spec conformance (CatalogPullCallbackAction.downloadManifest):</p>
     * <ul>
     *   <li>{@code checksum} is required; the DS MUST verify it before processing — absent → discard.</li>
     *   <li>The DS MUST NOT download after {@code expiresAt} — absent/expired → discard.</li>
     *   <li>{@code checksum} is the SHA-256 digest of the payload <em>at url</em> (the downloaded,
     *       still-compressed bytes) and is verified BEFORE decompressing/decoding.</li>
     * </ul>
     *
     * @param becknContext the callback context node
     * @param manifest the downloadManifest node
     * @throws Exception if download, checksum verification, or decoding fails
     */
    private void processDownloadManifest(JsonNode becknContext, JsonNode manifest) throws Exception {
        metrics.recordOnPullReceived("download");
        String downloadUrl = manifest.path("url").asText("");
        String fileFormat = manifest.path("format").asText("");
        String expectedChecksum = manifest.path("checksum").asText("");
        String expiresAt = manifest.path("expiresAt").asText("");

        // Spec: checksum is REQUIRED and the DS MUST verify it before processing.
        if (expectedChecksum.isBlank()) {
            log.warn("event={} reason=missing-checksum", LogEvent.ON_PULL_FAILED);
            metrics.recordOnPullFailed("missing_checksum");
            return;
        }
        // Spec: expiresAt is required and the DS MUST NOT download after it.
        if (expiresAt.isBlank()) {
            log.warn("event={} reason=missing-expiry", LogEvent.ON_PULL_FAILED);
            metrics.recordOnPullFailed("missing_expiry");
            return;
        }
        if (isExpired(expiresAt)) {
            log.warn("event={} reason=manifest-expired expiresAt={}", LogEvent.ON_PULL_FAILED, expiresAt);
            metrics.recordOnPullFailed("expired");
            return;
        }

        log.info("event={} url={} format={}", LogEvent.ON_PULL_DOWNLOAD_STARTED, redactUrl(downloadUrl), fileFormat);

        // downloadCatalogFromUrl delegates to the SEPARATE SecureCatalogDownloader bean (so Spring's
        // @Retryable proxy actually intercepts + retries transient failures) and is the single
        // stubbable download seam for tests. It applies the SSRF guard (IllegalArgumentException)
        // before the network call, then throws IOException on a non-200 response. Split the failure
        // types into distinct bounded reasons here, log + count, and rethrow the marker so the outer
        // catch does NOT also record processing_error. Behavior unchanged: the callback is discarded.
        byte[] payloadAtUrl;
        try {
            payloadAtUrl = downloadCatalogFromUrl(downloadUrl);
        } catch (IllegalArgumentException ssrf) {
            // SSRF guard rejected the (untrusted) manifest URL before any network call.
            log.warn("event={} reason={} url={}",
                    LogEvent.ON_PULL_SSRF_REJECT, ssrfReason(ssrf.getMessage()), redactUrl(downloadUrl));
            metrics.recordOnPullFailed("ssrf_reject");
            throw new AlreadyRecordedFailure(ssrf);
        } catch (SecureCatalogDownloader.SizeExceededException size) {
            // Download exceeded the hard cap (gzip-bomb / OOM guard). Distinct reason — must be caught
            // before the generic IOException below since SizeExceededException subtypes IOException.
            log.warn("event={} phase=download bytes={} limit={} url={}",
                    LogEvent.ON_PULL_SIZE_EXCEEDED, size.bytes(), size.limit(), redactUrl(downloadUrl));
            metrics.recordOnPullFailed("size_exceeded");
            throw new AlreadyRecordedFailure(size);
        } catch (IOException http) {
            // Non-200 HTTP response (or a transport IO failure) from the download.
            log.warn("event={} httpStatus={} url={} error={}",
                    LogEvent.ON_PULL_DOWNLOAD_HTTP_ERROR, extractHttpStatus(http.getMessage()),
                    redactUrl(downloadUrl), ErrorSanitizer.sanitize(http.getMessage()));
            metrics.recordOnPullFailed("download_http_error");
            throw new AlreadyRecordedFailure(http);
        }

        // Spec: checksum is the digest of the payload AT url; verify BEFORE decompressing.
        // If verification fails the DS MUST discard the content (verifySha256Checksum throws).
        try {
            verifySha256Checksum(payloadAtUrl, expectedChecksum);
        } catch (ChecksumMismatchException mismatch) {
            log.warn("event={} expected={} actual={}",
                    LogEvent.ON_PULL_CHECKSUM_MISMATCH, mismatch.expected(), mismatch.actual());
            metrics.recordOnPullFailed("checksum_mismatch");
            throw new AlreadyRecordedFailure(mismatch);
        }
        log.info("event={} sizeBytes={}", LogEvent.ON_PULL_CHECKSUM_VERIFIED, payloadAtUrl.length);

        byte[] catalogJsonBytes = payloadAtUrl;
        if ("json.gz".equalsIgnoreCase(fileFormat) || downloadUrl.endsWith(".gz")) {
            try {
                catalogJsonBytes = decompressGzipPayload(payloadAtUrl);
            } catch (SecureCatalogDownloader.SizeExceededException size) {
                // Decompressed output exceeded the hard cap (gzip-bomb / OOM guard). Distinct reason —
                // caught before the generic IOException below since it subtypes IOException.
                log.warn("event={} phase=decompress bytes={} limit={} compressedBytes={} url={}",
                        LogEvent.ON_PULL_SIZE_EXCEEDED, size.bytes(), size.limit(),
                        payloadAtUrl.length, redactUrl(downloadUrl));
                metrics.recordOnPullFailed("size_exceeded");
                throw new AlreadyRecordedFailure(size);
            } catch (IOException gz) {
                log.warn("event={} compressedBytes={} error={}",
                        LogEvent.ON_PULL_DECOMPRESS_ERROR, payloadAtUrl.length,
                        ErrorSanitizer.sanitize(gz.getMessage()));
                metrics.recordOnPullFailed("decompress_error");
                throw new AlreadyRecordedFailure(gz);
            }
            log.info("event={} compressedBytes={} decompressedBytes={}",
                    LogEvent.ON_PULL_DECOMPRESSED, payloadAtUrl.length, catalogJsonBytes.length);
        }

        JsonNode downloadedJsonRoot = objectMapper.readTree(catalogJsonBytes);
        JsonNode downloadedCatalogsArray = downloadedJsonRoot.path(BecknFields.CATALOGS);
        if (downloadedCatalogsArray.isArray() && !downloadedCatalogsArray.isEmpty()) {
            log.info("event={} mode=DOWNLOAD catalogsReturned={}",
                    LogEvent.ON_PULL_MODE_SELECTED, downloadedCatalogsArray.size());
            enqueueAndObserve(becknContext, downloadedCatalogsArray, "download");
            metrics.recordOnPullProcessed("download");
        } else {
            log.warn("event={} reason=no-catalogs-in-download", LogEvent.ON_PULL_FAILED);
            metrics.recordOnPullFailed("no_catalogs");
        }
    }

    /**
     * Populates MDC with the ORIGINAL correlation IDs from the CS callback context. The values
     * are taken verbatim from {@code context.messageId} / {@code context.transactionId} — never
     * generated locally — so every log line for this callback is correlatable end-to-end.
     */
    private void populateCallbackMdc(JsonNode becknContext) {
        putIfPresentMdc(MdcField.MESSAGE_ID, becknContext.path(BecknFields.MESSAGE_ID).asText(null));
        putIfPresentMdc(MdcField.TRANSACTION_ID, becknContext.path(BecknFields.TRANSACTION_ID).asText(null));
        putIfPresentMdc(MdcField.NETWORK_ID, becknContext.path(BecknFields.NETWORK_ID).asText(null));
    }

    private static void putIfPresentMdc(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /**
     * Returns {@code true} when the ISO-8601 {@code expiresAt} is in the past.
     *
     * <p>Lenient parse: an offset-bearing timestamp is parsed with {@link OffsetDateTime}; an
     * offset-less ISO-8601 timestamp (e.g. {@code "2099-12-31T23:59:59"}) — which
     * {@code OffsetDateTime.parse} rejects — falls back to {@link LocalDateTime} assumed to be UTC, so
     * a CS that emits offset-less timestamps is not treated as always-expired. Only a value that BOTH
     * parses fail on is treated as expired (fail-closed) so a genuinely malformed expiry never permits
     * a download.</p>
     */
    private boolean isExpired(String expiresAt) {
        OffsetDateTime expiry;
        try {
            expiry = OffsetDateTime.parse(expiresAt);
        } catch (DateTimeParseException withOffsetFailed) {
            try {
                // Offset-less ISO-8601 → assume UTC.
                expiry = LocalDateTime.parse(expiresAt).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException bothFailed) {
                // Genuinely unparseable → fail closed (treat as expired).
                return true;
            }
        }
        return expiry.isBefore(OffsetDateTime.now());
    }

    /**
     * Redacts a download URL for logging: returns {@code scheme://host/path} only, stripping the query
     * string. GCS V4 signed URLs carry {@code X-Goog-Signature} (a bearer capability) in the query, so
     * logging the raw URL would leak a credential. Best-effort — on any parse failure returns a fixed
     * placeholder rather than risking leaking the raw (possibly signed) URL. Does NOT change the URL
     * actually fetched.
     */
    private static String redactUrl(String url) {
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

    /**
     * Classifies an SSRF-guard rejection into a bounded reason tag/label for logging. Derived from
     * the {@link #validateDownloadUrl} messages — no unbounded/user-controlled value leaks in.
     */
    private static String ssrfReason(String message) {
        if (message == null) {
            return "unknown";
        }
        if (message.contains("scheme")) {
            return "scheme";
        }
        if (message.contains("no host")) {
            return "host";
        }
        if (message.contains("private/loopback")) {
            return "private";
        }
        if (message.contains("could not be resolved")) {
            return "unresolvable";
        }
        if (message.contains("Malformed")) {
            return "malformed";
        }
        return "unknown";
    }

    /**
     * Extracts the HTTP status from a {@link #downloadCatalogFromUrl} non-200 message
     * ("... - HTTP {code}"), or {@code "unknown"} for a transport-level IO failure with no status.
     */
    private static String extractHttpStatus(String message) {
        if (message != null) {
            int idx = message.lastIndexOf("HTTP ");
            if (idx >= 0) {
                return message.substring(idx + "HTTP ".length()).trim();
            }
        }
        return "unknown";
    }

    /**
     * Enqueues a SINGLE catalog onto the ingestion topic for persistence and indexing, wrapping the
     * ORIGINAL callback context (deep-copied, {@code context.action} = {@code catalog/on_pull}
     * preserved) around a one-catalog {@code catalogs[]} array. The publish pipeline does not key off
     * {@code action}, so no re-stamping is needed.
     *
     * <p>C2: each catalog becomes its OWN Kafka record. A large downloadManifest result can hold many
     * catalogs; enqueuing the whole array as one record guarantees {@code RecordTooLargeException} on
     * the ingestion topic and loses the entire (already downloaded/checksummed/decompressed) pull
     * result. Per-catalog records keep each message small. The ingestion topic is keyed by
     * {@code context.subscriberId} (see {@link CatalogPushService#enqueueForProcessing}), so all of a
     * subscriber's per-catalog records still route to the same partition and stay ordered.</p>
     *
     * @param becknContext the original callback context node
     * @param catalogNode the single catalog node to ingest
     * @throws IOException if JSON serialization fails
     */
    /** Builds the per-catalog ingestion record ({@code context} + one-element {@code catalogs[]}). */
    private String buildSingleCatalogRecord(JsonNode becknContext, JsonNode catalogNode) throws IOException {
        ObjectNode newContext = (ObjectNode) becknContext.deepCopy();

        ObjectNode newRoot = objectMapper.createObjectNode();
        newRoot.set(BecknFields.CONTEXT, newContext);

        ObjectNode newMessage = objectMapper.createObjectNode();
        ArrayNode singleCatalogArray = objectMapper.createArrayNode();
        singleCatalogArray.add(catalogNode.deepCopy());
        newMessage.set(BecknFields.CATALOGS, singleCatalogArray);
        newRoot.set(BecknFields.MESSAGE, newMessage);

        return objectMapper.writeValueAsString(newRoot);
    }

    /**
     * Receiver-level observability + per-catalog enqueue: emits per-catalog INFO logs (with
     * {@code catalogId} + {@code networkId} in MDC) and metrics (catalogs returned, resources total,
     * accepted / rejected / processed), and enqueues EACH catalog as its OWN Kafka record
     * (C2 — never one giant record → no {@code RecordTooLargeException}).
     *
     * <p>{@code accepted} is recorded once a catalog has a non-blank id AND fits the configured
     * record cap; {@code processed} is recorded after its per-catalog enqueue. A catalog that is
     * missing an id, or whose serialized record exceeds {@code app.catalog.max-payload-size} (the
     * SAME cap the Kafka producer is configured with), is a clean {@code rejected} — checked BEFORE
     * enqueue so a single oversized catalog (possible on the download path) can never throw a
     * runtime {@code RecordTooLargeException} at send. The persisted count is decided downstream.</p>
     */
    private void enqueueAndObserve(JsonNode becknContext, JsonNode catalogArray, String mode) throws IOException {
        int catalogsReturned = catalogArray.size();
        metrics.recordOnPullCatalogsReturned(mode, catalogsReturned);

        long maxRecordBytes = props.catalog().maxPayloadSize();
        int resourcesTotal = 0;
        int accepted = 0;
        int processed = 0;
        for (JsonNode catalogNode : catalogArray) {
            String id = catalogNode.isObject() ? catalogNode.path(BecknFields.ID).asText(null) : null;
            if (id == null || id.isBlank()) {
                log.warn("event={} reason=missing-id", LogEvent.ON_PULL_CATALOG_REJECTED);
                metrics.recordOnPullCatalogRejected(mode);
                continue;
            }
            MDC.put(MdcField.CATALOG_ID, id);
            try {
                // Build + size-guard the per-catalog record against the producer's configured cap
                // BEFORE sending — an oversized single catalog is a clean rejection, not a send-time throw.
                String record = buildSingleCatalogRecord(becknContext, catalogNode);
                int recordBytes = record.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (recordBytes > maxRecordBytes) {
                    log.warn("event={} reason=too_large sizeBytes={} limit={}",
                            LogEvent.ON_PULL_CATALOG_REJECTED, recordBytes, maxRecordBytes);
                    metrics.recordOnPullCatalogRejected(mode);
                    continue;
                }
                int resourceCount = catalogNode.path(BecknFields.RESOURCES).size();
                resourcesTotal += resourceCount;
                accepted++;
                metrics.recordOnPullCatalogAccepted(mode);
                // C2: enqueue THIS catalog as its own Kafka record (never one giant array record).
                pushService.enqueueForProcessing(record);
                processed++;
                metrics.recordOnPullCatalogProcessed(mode);
                log.info("event={} resourceCount={}", LogEvent.ON_PULL_CATALOG_ENQUEUED, resourceCount);
            } finally {
                MDC.remove(MdcField.CATALOG_ID);
            }
        }
        metrics.recordOnPullResourcesTotal(mode, resourcesTotal);

        log.info("event={} mode={} catalogsReturned={} accepted={} processed={} resourcesTotal={}",
                LogEvent.ON_PULL_COMPLETED, mode, catalogsReturned, accepted, processed, resourcesTotal);
    }

    /** Records the publisher's {@code pagination.total} (claimed grand total) only when present — never defaults to 0. */
    private void recordPaginationIfPresent(String mode, JsonNode becknMessage) {
        JsonNode total = becknMessage.path("pagination").path("total");
        if (total.isNumber()) {
            metrics.recordOnPullPaginationTotal(mode, total.asLong());
        }
    }

    /**
     * Downloads catalog bytes from a URL via the {@link SecureCatalogDownloader} bean (SSRF guard +
     * bounded {@code @Retryable} retry + hard size cap). Kept as a thin delegate so callers/tests have
     * a single entry point; the retry/SSRF logic itself lives in the separate bean so Spring's retry
     * proxy actually intercepts it.
     *
     * @param downloadUrl the HTTP URL to download from
     * @return the raw downloaded bytes
     * @throws Exception if the SSRF guard rejects the URL, the size cap is exceeded, or (after retries
     *         are exhausted) the HTTP call fails
     */
    public byte[] downloadCatalogFromUrl(String downloadUrl) throws Exception {
        return downloader.download(downloadUrl);
    }

    /**
     * Decompresses gzip compressed bytes.
     *
     * @param compressedGzipData the gzip-compressed bytes
     * @return the uncompressed byte content
     * @throws IOException if decompression fails
     */
    private byte[] decompressGzipPayload(byte[] compressedGzipData) throws IOException {
        long maxDecompressedBytes = props.catalog().pullMaxDecompressedBytes();
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressedGzipData));
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            long total = 0;
            int len;
            // Track total decompressed output and abort as soon as it exceeds the cap, so a
            // gzip-bomb (tiny compressed input expanding to gigabytes) can never OOM the DS.
            while ((len = gis.read(buffer)) > 0) {
                total += len;
                if (total > maxDecompressedBytes) {
                    throw new SecureCatalogDownloader.SizeExceededException("decompressed", total, maxDecompressedBytes);
                }
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Computes the SHA-256 hash of the bytes and validates it against the expected checksum.
     *
     * @param decompressedData the bytes to checksum
     * @param expectedChecksumHash the expected hash checksum (optionally prefixed with "sha256:")
     * @throws ChecksumMismatchException (an {@link IOException}) if the digest does not match
     */
    private void verifySha256Checksum(byte[] decompressedData, String expectedChecksumHash) throws IOException {
        String expectedHash = expectedChecksumHash;
        if (expectedChecksumHash.startsWith("sha256:")) {
            expectedHash = expectedChecksumHash.substring("sha256:".length());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(decompressedData);
            String actualHash = java.util.HexFormat.of().formatHex(digest);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new ChecksumMismatchException(expectedHash, actualHash);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
