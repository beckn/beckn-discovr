package org.beckn.seeker.service.signature;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Component for building Beckn HTTP Signature headers.
 * Formats signing strings and authorization headers according to Beckn protocol specification.
 */
@Slf4j
@Component
public class SignatureHeaderBuilder {

    private static final String ALGORITHM = "ed25519";
    private static final String KEY_ID_FORMAT = "%s|%s|" + ALGORITHM;
    private static final String SIGNING_STRING_FORMAT = "(created): %d\n(expires): %d\ndigest: BLAKE-512=%s";
    private static final String AUTH_HEADER_FORMAT = "Signature keyId=\"%s\",algorithm=\"" + ALGORITHM + "\",created=\"%d\",expires=\"%d\",headers=\"(created) (expires) digest\",signature=\"%s\"";

    /**
     * Builds the signing string for signature generation.
     * Format: (created): {timestamp}\n(expires): {timestamp}\ndigest: BLAKE-512={digest}
     *
     * @param created timestamp when signature was created (Unix epoch seconds)
     * @param expires timestamp when signature expires (Unix epoch seconds)
     * @param digest Base64-encoded BLAKE-512 digest of the message
     * @return formatted signing string
     */
    public String buildSigningString(long created, long expires, String digest) {
        return String.format(SIGNING_STRING_FORMAT, created, expires, digest);
    }

    /**
     * Builds the Authorization header value in Beckn signature format.
     * <p>
     * Format: Signature keyId="{subscriberId}|{uniqueKeyId}|ed25519",algorithm="ed25519",created="{timestamp}",expires="{timestamp}",headers="(created) (expires) digest",signature="{signature}"
     *
     * @param subscriberId the subscriber ID (bap_id or bpp_id)
     * @param uniqueKeyId the unique key identifier
     * @param created timestamp when signature was created
     * @param expires timestamp when signature expires
     * @param signature Base64-encoded Ed25519 signature
     * @return formatted Authorization header value
     */
    public String buildAuthorizationHeader(String subscriberId, String uniqueKeyId, long created, long expires, String signature) {
        String keyId = String.format(KEY_ID_FORMAT, subscriberId, uniqueKeyId);
        return String.format(AUTH_HEADER_FORMAT, keyId, created, expires, signature);
    }
}
