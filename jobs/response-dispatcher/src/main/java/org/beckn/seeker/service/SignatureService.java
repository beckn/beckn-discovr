package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.beckn.seeker.logging.LogEvent;
import org.beckn.seeker.service.signature.CryptoService;
import org.beckn.seeker.service.signature.PrivateKeyLoader;
import org.beckn.seeker.service.signature.SignatureHeaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.PrivateKey;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Service for generating Beckn HTTP Signatures using Ed25519 cryptographic algorithm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureService {

    private final ObjectMapper objectMapper;
    private final PrivateKeyLoader privateKeyLoader;
    private final CryptoService cryptoService;
    private final SignatureHeaderBuilder signatureHeaderBuilder;

    @Value("${signing.enabled:false}")
    private boolean enabled;

    @Value("${signing.subscriber-id:}")
    private String subscriberId;

    @Value("${signing.key-id-suffix:}")
    private String uniqueKeyId;

    @Value("${signing.private-key:}")
    private String privateKeyPem;

    @Value("${signing.expiry-seconds:3600}")
    private long expirySeconds;

    private PrivateKey parsedPrivateKey;

    /**
     * Initializes the signature service at application startup.
     * Validates configuration and loads the private key if signature is enabled.
     * Fails fast if configuration is invalid to prevent deployment issues.
     *
     * @throws IllegalStateException if configuration is invalid or key loading fails
     */
    @PostConstruct
    public void init() {
        if (enabled) {
            log.info("{}", value("event", LogEvent.SIGNATURE_INIT),
                    value("keyIdSuffix", uniqueKeyId));
            validateConfiguration();
            try {
                parsedPrivateKey = privateKeyLoader.loadPrivateKey(privateKeyPem);
                log.info("{}", value("event", LogEvent.SIGNATURE_GENERATED),
                        value("status", "initialized"));
            } catch (Exception e) {
                log.error("{}", value("event", LogEvent.SIGNATURE_FAILED),
                        value("errorMessage", e.getMessage()));
                throw new IllegalStateException("Invalid signature configuration: " + e.getMessage(), e);
            }
        } else {
            log.debug("{}", value("event", LogEvent.SIGNATURE_DISABLED));
        }
    }

    /**
     * Validates that required configuration properties are present.
     *
     * @throws IllegalStateException if unique-key-id or private-key is missing
     */
    private void validateConfiguration() {
        if (subscriberId == null || subscriberId.trim().isEmpty()) {
            throw new IllegalStateException("signing.subscriber-id is required when signing.enabled=true");
        }
        if (uniqueKeyId == null || uniqueKeyId.trim().isEmpty()) {
            throw new IllegalStateException("signing.key-id-suffix is required when signing.enabled=true");
        }
        if (privateKeyPem == null || privateKeyPem.trim().isEmpty()) {
            throw new IllegalStateException("signing.private-key is required when signing.enabled=true");
        }
    }

    /**
     * Checks if signature generation is enabled.
     *
     * @return true if signing.enabled=true, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Generates Beckn HTTP Signature Authorization header for outgoing requests.
     * Uses Ed25519 signing with BLAKE-512 digest according to Beckn protocol specification.
     *
     * @param requestBody the complete message body to sign (JSON string)
     * @return Authorization header value in Beckn signature format
     * @throws RuntimeException if signature generation fails
     */
    public String generateAuthHeader(String requestBody) {
        try {
            long created = System.currentTimeMillis() / 1000;
            long expires = created + expirySeconds;

            String digest = cryptoService.hashMessage(requestBody);
            String signingString = signatureHeaderBuilder.buildSigningString(created, expires, digest);
            String signature = cryptoService.sign(signingString, parsedPrivateKey);

            String header = signatureHeaderBuilder.buildAuthorizationHeader(subscriberId, uniqueKeyId, created, expires, signature);
            log.debug("{}", value("event", LogEvent.SIGNATURE_GENERATED));
            return header;
        } catch (Exception e) {
            log.error("{}", value("event", LogEvent.SIGNATURE_FAILED),
                    value("errorMessage", e.getMessage()), e);
            throw new RuntimeException("Signature generation failed", e);
        }
    }
}
