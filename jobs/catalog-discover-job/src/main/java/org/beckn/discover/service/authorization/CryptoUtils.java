package org.beckn.discover.service.authorization;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Authorization Crypto Utilities
 * Pure cryptographic functions for Beckn HTTP Signatures (Ed25519 + BLAKE-512)
 */
@Component
public class CryptoUtils {

    private static final Logger logger = LoggerFactory.getLogger(CryptoUtils.class);
    private static final int RAW_ED25519_PUBLIC_KEY_LENGTH = 32;
    private KeyFactory ed25519KeyFactory;

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.info("Bouncy Castle provider registered for crypto operations");
        }
        try {
            this.ed25519KeyFactory = KeyFactory.getInstance("Ed25519");
        } catch (Exception e) {
            logger.error("Failed to initialize Ed25519 KeyFactory", e);
            throw new RuntimeException("Crypto initialization failed", e);
        }
    }

    /**
     * Generate BLAKE-512 digest of a payload
     * 
     * @param payload The string payload to hash
     * @return Base64 encoded hash digest
     */
    public String generateHash(String payload) {
        Blake2bDigest digest = new Blake2bDigest(512);
        byte[] messageBytes = payload.getBytes(StandardCharsets.UTF_8);
        digest.update(messageBytes, 0, messageBytes.length);
        byte[] hash = new byte[64];
        digest.doFinal(hash, 0);
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Create Beckn signing string format
     * 
     * @param created Timestamp of creation
     * @param expires Timestamp of expiration
     * @param digest  The hash digest of the payload
     * @return formatted signing string
     */
    public String createSigningString(long created, long expires, String digest) {
        return "(created): " + created + "\n(expires): " + expires + "\ndigest: BLAKE-512=" + digest;
    }

    /**
     * Parse PEM formatted public key.
     * Supports both:
     * - X.509 SPKI format (44 bytes): Standard format with ASN.1 header
     * - Raw Ed25519 format (32 bytes): Wraps in X.509 format before parsing
     */
    public PublicKey parsePublicKey(String publicKeyPem) {
        try {
            String keyContent = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            
            if (keyBytes.length == RAW_ED25519_PUBLIC_KEY_LENGTH) {
                keyBytes = wrapRawKeyInX509(keyBytes);
            }
            
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            return this.ed25519KeyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            logger.error("Failed to parse public key: {}", e.getMessage());
            throw new RuntimeException("Invalid public key format", e);
        }
    }

    /**
     * Wrap raw 32-byte Ed25519 public key in X.509 SPKI format using BouncyCastle.
     */
    private byte[] wrapRawKeyInX509(byte[] rawKey) {
        try {
            AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519);
            SubjectPublicKeyInfo publicKeyInfo = new SubjectPublicKeyInfo(algorithmIdentifier, rawKey);
            return publicKeyInfo.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to wrap raw key in X.509 format", e);
        }
    }

    /**
     * Verify a signature using a pre-parsed PublicKey.
     * <p>
     * <b>Zero-Copy Optimization:</b>
     * Takes the cached {@link PublicKey} directly. Skips the overhead of parsing
     * PEM,
     * decoding Base64, and generating KeySpecs.
     * 
     * @param signingString   The exact string payload that was signed
     * @param signatureBase64 The Base64 signature from the header
     * @param publicKey       The cached Ed25519 Public Key object
     * @return true if valid, false otherwise
     */
    public boolean verifySignature(String signingString, String signatureBase64, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(signingString.getBytes(StandardCharsets.UTF_8));

            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            logger.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

}
