package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.zip.GZIPInputStream;

/**
 * Service responsible for processing incoming Beckn catalog {@code on_pull} callbacks asynchronously.
 * Supports processing inline catalogs and downloading, decompressing, and verifying catalog assets
 * referenced in a {@code downloadManifest}.
 */
@Service
public class CatalogPullCallbackService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPullCallbackService.class);

    private final CatalogPushService pushService;
    private final ObjectMapper objectMapper;
    private final CatalogPublishMetrics metrics;
    private final AppProperties props;
    private final HttpClient httpClient;

    /**
     * Constructs the callback processing service.
     *
     * @param pushService the service used to enqueue transformed catalogs
     * @param objectMapper the JSON object mapper
     * @param metrics publish/on_pull metrics recorder
     * @param props application configuration (gates the on_pull download SSRF guard)
     */
    public CatalogPullCallbackService(CatalogPushService pushService, ObjectMapper objectMapper,
            CatalogPublishMetrics metrics, AppProperties props) {
        this.pushService = pushService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Asynchronously processes the raw string payload of a Beckn {@code on_pull} callback.
     * Evaluates the callback status, extracts/downloads catalogs, and pushes them to the ingestion pipeline.
     *
     * @param rawCallbackPayload the raw JSON request body received at the on_pull endpoint
     */
    @Async("catalogProcessingExecutor")
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

        log.info("event={} url={} format={}", LogEvent.ON_PULL_DOWNLOAD_STARTED, downloadUrl, fileFormat);

        // downloadCatalogFromUrl applies the SSRF guard before the network call.
        byte[] payloadAtUrl = downloadCatalogFromUrl(downloadUrl);

        // Spec: checksum is the digest of the payload AT url; verify BEFORE decompressing.
        // If verification fails the DS MUST discard the content (verifySha256Checksum throws).
        verifySha256Checksum(payloadAtUrl, expectedChecksum);
        log.info("event={} sizeBytes={}", LogEvent.ON_PULL_CHECKSUM_VERIFIED, payloadAtUrl.length);

        byte[] catalogJsonBytes = payloadAtUrl;
        if ("json.gz".equalsIgnoreCase(fileFormat) || downloadUrl.endsWith(".gz")) {
            catalogJsonBytes = decompressGzipPayload(payloadAtUrl);
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
        putIfPresentMdc(MdcField.NETWORK_ID, becknContext.path(MdcField.NETWORK_ID).asText(null));
    }

    private static void putIfPresentMdc(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /**
     * Returns {@code true} when the ISO-8601 {@code expiresAt} is in the past. Fails closed
     * (treats an unparseable timestamp as expired) so a malformed expiry never permits a download.
     */
    private boolean isExpired(String expiresAt) {
        try {
            return OffsetDateTime.parse(expiresAt).isBefore(OffsetDateTime.now());
        } catch (java.time.format.DateTimeParseException e) {
            return true;
        }
    }

    /**
     * Enqueues the callback's catalogs onto the ingestion topic for persistence and indexing.
     * The original {@code context.action} ({@code catalog/on_pull}) is preserved — the publish
     * pipeline does not key off {@code action}, so no re-stamping is needed.
     *
     * @param becknContext the original callback context node
     * @param catalogArray the catalog array node to ingest
     * @throws IOException if JSON serialization fails
     */
    private void transformContextAndEnqueueCatalogs(JsonNode becknContext, JsonNode catalogArray) throws IOException {
        ObjectNode newContext = (ObjectNode) becknContext.deepCopy();

        ObjectNode newRoot = objectMapper.createObjectNode();
        newRoot.set(BecknFields.CONTEXT, newContext);
        
        ObjectNode newMessage = objectMapper.createObjectNode();
        newMessage.set(BecknFields.CATALOGS, catalogArray);
        newRoot.set(BecknFields.MESSAGE, newMessage);

        String transformedJson = objectMapper.writeValueAsString(newRoot);
        pushService.enqueueForProcessing(transformedJson);
    }

    /**
     * Receiver-level observability: emits per-catalog INFO logs (with {@code catalogId} +
     * {@code networkId} in MDC) and metrics (catalogs returned, resources total, accepted /
     * rejected / processed), then enqueues the whole array ONCE via
     * {@link #transformContextAndEnqueueCatalogs}. Iteration is for observation only — the
     * single-enqueue contract and the catalogs payload are unchanged. "accepted"/"processed"
     * are receiver-level (accepted-for-ingestion); the persisted count is decided downstream.
     */
    private void enqueueAndObserve(JsonNode becknContext, JsonNode catalogArray, String mode) throws IOException {
        int catalogsReturned = catalogArray.size();
        metrics.recordOnPullCatalogsReturned(mode, catalogsReturned);

        int resourcesTotal = 0;
        int accepted = 0;
        for (JsonNode catalogNode : catalogArray) {
            String id = catalogNode.isObject() ? catalogNode.path(BecknFields.ID).asText(null) : null;
            if (id == null || id.isBlank()) {
                log.warn("event={} reason=missing-id", LogEvent.ON_PULL_CATALOG_REJECTED);
                metrics.recordOnPullCatalogRejected(mode);
                continue;
            }
            MDC.put(MdcField.CATALOG_ID, id);
            try {
                int resourceCount = catalogNode.path(BecknFields.RESOURCES).size();
                resourcesTotal += resourceCount;
                accepted++;
                metrics.recordOnPullCatalogAccepted(mode);
                log.info("event={} resourceCount={}", LogEvent.ON_PULL_CATALOG_ENQUEUED, resourceCount);
                metrics.recordOnPullCatalogProcessed(mode);
            } finally {
                MDC.remove(MdcField.CATALOG_ID);
            }
        }
        metrics.recordOnPullResourcesTotal(mode, resourcesTotal);

        // Single enqueue of the whole array (unchanged contract).
        transformContextAndEnqueueCatalogs(becknContext, catalogArray);

        log.info("event={} mode={} catalogsReturned={} accepted={} processed={} resourcesTotal={}",
                LogEvent.ON_PULL_COMPLETED, mode, catalogsReturned, accepted, accepted, resourcesTotal);
    }

    /** Records the publisher's {@code pagination.total} (claimed grand total) only when present — never defaults to 0. */
    private void recordPaginationIfPresent(String mode, JsonNode becknMessage) {
        JsonNode total = becknMessage.path("pagination").path("total");
        if (total.isNumber()) {
            metrics.recordOnPullPaginationTotal(mode, total.asLong());
        }
    }

    /**
     * Downloads catalog bytes from a URL.
     *
     * @param downloadUrl the HTTP URL to download from
     * @return the raw downloaded bytes
     * @throws Exception if HTTP call fails or response code is not 200
     */
    public byte[] downloadCatalogFromUrl(String downloadUrl) throws Exception {
        // SSRF guard: validate the (untrusted) manifest URL before any network call.
        validateDownloadUrl(downloadUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download catalog file from " + downloadUrl + " - HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * SSRF guard for the (untrusted) {@code downloadManifest.url}. Allows only http/https with a
     * resolvable, non-private host. Fails closed: an unresolvable host is rejected rather than fetched.
     *
     * @param url the manifest download URL
     * @throws IllegalArgumentException if the URL is malformed, non-http(s), or resolves to a
     *         loopback / link-local / site-local (private) address
     */
    private void validateDownloadUrl(String url) {
        // Secure-by-default gate. When app.catalog.pull-ssrf-check-enabled is explicitly false
        // (local/dev), skip the guard and WARN that it is disabled. Absent/true => enforce as today.
        if (Boolean.FALSE.equals(props.catalog().pullSsrfCheckEnabled())) {
            log.warn("event={} reason=ssrf-guard-disabled url={}", LogEvent.ON_PULL_SSRF_DISABLED, url);
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
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                throw new IllegalArgumentException("Download URL points to private/loopback address: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            // Fail closed: an unresolvable host cannot be verified safe.
            throw new IllegalArgumentException("Download URL host could not be resolved: " + host, e);
        }
    }

    /**
     * Decompresses gzip compressed bytes.
     *
     * @param compressedGzipData the gzip-compressed bytes
     * @return the uncompressed byte content
     * @throws IOException if decompression fails
     */
    private byte[] decompressGzipPayload(byte[] compressedGzipData) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressedGzipData));
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gis.read(buffer)) > 0) {
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
     * @throws IOException if verification fails
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
                throw new IOException("SHA-256 checksum verification failed. Expected: " + expectedHash + ", Actual: " + actualHash);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
