package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
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
    private final HttpClient httpClient;

    /**
     * Constructs the callback processing service.
     *
     * @param pushService the service used to enqueue transformed catalogs
     * @param objectMapper the JSON object mapper
     */
    public CatalogPullCallbackService(CatalogPushService pushService, ObjectMapper objectMapper) {
        this.pushService = pushService;
        this.objectMapper = objectMapper;
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

            String callbackStatus = becknMessage.path("status").asText("");
            if ("FAILED".equalsIgnoreCase(callbackStatus)) {
                JsonNode callbackError = becknMessage.path("error");
                log.warn("event={} status=FAILED transactionId={} errorCode={} errorMessage={}",
                        LogEvent.ON_PULL_FAILED,
                        becknContext.path(BecknFields.TRANSACTION_ID).asText(""),
                        callbackError.path("code").asText(""),
                        callbackError.path("message").asText(""));
                return;
            }

            JsonNode inlineCatalogsArray = becknMessage.path(BecknFields.CATALOGS);
            JsonNode downloadManifestNode = becknMessage.path("downloadManifest");

            if (inlineCatalogsArray.isArray() && !inlineCatalogsArray.isEmpty()) {
                log.info("event={} mode=INLINE transactionId={} catalogCount={}",
                        LogEvent.ON_PULL_RECEIVED,
                        becknContext.path(BecknFields.TRANSACTION_ID).asText(""),
                        inlineCatalogsArray.size());
                transformContextAndEnqueueCatalogs(becknContext, inlineCatalogsArray);
            } else if (!downloadManifestNode.isMissingNode() && !downloadManifestNode.isNull()) {
                String manifestDownloadUrl = downloadManifestNode.path("url").asText("");
                String manifestFileFormat = downloadManifestNode.path("format").asText("");
                String manifestFileChecksum = downloadManifestNode.path("checksum").asText("");
                log.info("event={} mode=DOWNLOAD transactionId={} url={} format={}",
                        LogEvent.ON_PULL_RECEIVED,
                        becknContext.path(BecknFields.TRANSACTION_ID).asText(""),
                        manifestDownloadUrl, manifestFileFormat);
                
                byte[] downloadedCompressedBytes = downloadCatalogFromUrl(manifestDownloadUrl);
                byte[] decompressedBytes = downloadedCompressedBytes;
                if ("json.gz".equalsIgnoreCase(manifestFileFormat) || manifestDownloadUrl.endsWith(".gz")) {
                    decompressedBytes = decompressGzipPayload(downloadedCompressedBytes);
                }

                if (manifestFileChecksum != null && !manifestFileChecksum.isBlank()) {
                    verifySha256Checksum(decompressedBytes, manifestFileChecksum);
                }

                JsonNode downloadedJsonRoot = objectMapper.readTree(decompressedBytes);
                JsonNode downloadedCatalogsArray = downloadedJsonRoot.path(BecknFields.CATALOGS);
                if (downloadedCatalogsArray.isArray() && !downloadedCatalogsArray.isEmpty()) {
                    log.info("event={} mode=DOWNLOAD_SUCCESS transactionId={} catalogCount={}",
                            LogEvent.ON_PULL_RECEIVED,
                            becknContext.path(BecknFields.TRANSACTION_ID).asText(""),
                            downloadedCatalogsArray.size());
                    transformContextAndEnqueueCatalogs(becknContext, downloadedCatalogsArray);
                } else {
                    log.warn("event={} reason=no-catalogs-in-download transactionId={}",
                            LogEvent.ON_PULL_FAILED,
                            becknContext.path(BecknFields.TRANSACTION_ID).asText(""));
                }
            } else {
                log.warn("event={} reason=empty-callback transactionId={}",
                        LogEvent.ON_PULL_FAILED,
                        becknContext.path(BecknFields.TRANSACTION_ID).asText(""));
            }

        } catch (Exception e) {
            log.error("event={} error={}",
                    LogEvent.ON_PULL_FAILED,
                    ErrorSanitizer.sanitize(e.getMessage()),
                    e);
        }
    }

    /**
     * Standardizes context headers to catalog/push and enqueues transformed catalogs for persistence and indexing.
     *
     * @param becknContext the original callback context node
     * @param catalogArray the catalog array node to ingest
     * @throws IOException if JSON serialization fails
     */
    private void transformContextAndEnqueueCatalogs(JsonNode becknContext, JsonNode catalogArray) throws IOException {
        ObjectNode newContext = (ObjectNode) becknContext.deepCopy();
        // Standardize action to catalog/push so that existing pipeline processes it correctly
        newContext.put(BecknFields.ACTION, BecknFields.ACTION_CATALOG_PUBLISH);

        ObjectNode newRoot = objectMapper.createObjectNode();
        newRoot.set(BecknFields.CONTEXT, newContext);
        
        ObjectNode newMessage = objectMapper.createObjectNode();
        newMessage.set(BecknFields.CATALOGS, catalogArray);
        newRoot.set(BecknFields.MESSAGE, newMessage);

        String transformedJson = objectMapper.writeValueAsString(newRoot);
        pushService.enqueueForProcessing(transformedJson);
    }

    /**
     * Downloads catalog bytes from a URL.
     *
     * @param downloadUrl the HTTP URL to download from
     * @return the raw downloaded bytes
     * @throws Exception if HTTP call fails or response code is not 200
     */
    public byte[] downloadCatalogFromUrl(String downloadUrl) throws Exception {
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
