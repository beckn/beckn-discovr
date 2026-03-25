package org.beckn.seeker.service.signature;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Component for loading and parsing Ed25519 private keys from PEM format.
 */
@Slf4j
@Component
public class PrivateKeyLoader {

    private static final String ALGORITHM_ED25519 = "Ed25519";
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final int PKCS8_KEY_LENGTH = 48;
    private static final int RAW_SEED_LENGTH = 32;

    /**
     * Loads an Ed25519 private key from PEM format string or raw Base64.
     * Supports multiple formats:
     * - PKCS#8 PEM with headers (48 bytes decoded)
     * - PKCS#8 raw Base64 (48 bytes)
     * - Raw 32-byte seed in Base64
     *
     * @param privateKeyPem the private key in PEM format or raw Base64
     * @return parsed PrivateKey instance
     * @throws Exception if key parsing fails
     */
    public PrivateKey loadPrivateKey(String privateKeyPem) throws Exception {
        String keyContent = privateKeyPem
            .replace(PEM_HEADER, "")
            .replace(PEM_FOOTER, "")
            .replaceAll("\\s", "")
            .replaceAll("\\n", "");
        
        if (keyContent.isEmpty()) {
            throw new IllegalArgumentException("Private key content is empty");
        }
        
        byte[] decodedKeyBytes = Base64.getDecoder().decode(keyContent);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM_ED25519);
        
        // Try PKCS#8 format first (48 bytes)
        if (decodedKeyBytes.length == PKCS8_KEY_LENGTH) {
            log.debug("Loading PKCS#8 format private key ({} bytes)", PKCS8_KEY_LENGTH);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decodedKeyBytes));
        }
        
        // Handle raw 32-byte seed using EdECPrivateKeySpec
        if (decodedKeyBytes.length == RAW_SEED_LENGTH) {
            log.debug("Loading raw Ed25519 seed ({} bytes) using EdECPrivateKeySpec", RAW_SEED_LENGTH);
            EdECPrivateKeySpec keySpec = new EdECPrivateKeySpec(
                new NamedParameterSpec("Ed25519"),
                decodedKeyBytes
            );
            return keyFactory.generatePrivate(keySpec);
        }
        
        throw new IllegalArgumentException(
            String.format("Invalid private key length: %d bytes. Expected %d (raw seed) or %d (PKCS#8)", 
                decodedKeyBytes.length, RAW_SEED_LENGTH, PKCS8_KEY_LENGTH)
        );
    }
}
