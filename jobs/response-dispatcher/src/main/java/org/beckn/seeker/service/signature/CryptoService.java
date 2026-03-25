package org.beckn.seeker.service.signature;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.util.Base64;

/**
 * Service for cryptographic operations.
 * Provides message digest and digital signature functionality.
 */
@Slf4j
@Component
public class CryptoService {

    private static final String ALGORITHM_ED25519 = "Ed25519";
    private static final String ALGORITHM_BLAKE2B = "BLAKE2b-512";

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            log.info("BouncyCastle provider registered for BLAKE2b-512 support");
        }
    }

    /**
     * Generates hash digest of the message using BLAKE2b-512 algorithm.
     *
     * @param message the message to hash
     * @return Base64-encoded hash digest
     * @throws Exception if hashing algorithm is not available
     */
    public String hashMessage(String message) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(ALGORITHM_BLAKE2B, BouncyCastleProvider.PROVIDER_NAME);
        return Base64.getEncoder().encodeToString(digest.digest(message.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Signs the message using Ed25519 algorithm with the provided private key.
     *
     * @param message the message to sign
     * @param privateKey the Ed25519 private key
     * @return Base64-encoded signature
     * @throws Exception if signing fails
     */
    public String sign(String message, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance(ALGORITHM_ED25519);
        sig.initSign(privateKey);
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }
}
