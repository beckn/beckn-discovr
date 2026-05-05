package org.beckn.catalogpublish.indexing.bulk;

/**
 * Thrown when Elasticsearch returns HTTP 429 (Too Many Requests).
 *
 * <p>Declared as a separate type so that {@link BulkIndexService}'s
 * {@code @Retryable} annotation can include it alongside
 * {@link java.net.ConnectException} and {@link java.net.SocketTimeoutException}
 * without widening the retry scope to all {@link RuntimeException}s.</p>
 */
public class EsRateLimitException extends RuntimeException {

    public EsRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
