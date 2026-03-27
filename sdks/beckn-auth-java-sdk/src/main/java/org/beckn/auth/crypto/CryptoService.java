package org.beckn.auth.crypto;

import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.Logger;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Thread-safe cryptographic service for the Beckn Auth SDK.
 * <p>
 * Consolidates all cryptographic operations needed for Beckn HTTP Signatures:
 * </p>
 * <ul>
 * <li><b>Hashing:</b> BLAKE2b-512 via BouncyCastle {@link Blake2bDigest} (same as
 * discovery-service-v2)</li>
 * <li><b>Signing:</b> Ed25519 via JDK {@link Signature}</li>
 * <li><b>Verification:</b> Ed25519 via JDK {@link Signature}</li>
 * <li><b>Public key parsing:</b> raw 32-byte keys wrapped in X.509 SPKI using
 * BouncyCastle {@link SubjectPublicKeyInfo} (same as discovery-service-v2)</li>
 * <li><b>Private key parsing:</b> raw 32-byte seeds handled via JDK
 * {@link EdECPrivateKeySpec} (same as response-dispatcher PrivateKeyLoader)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>
 * All methods are stateless. {@link Signature} instances are created per-call
 * (never shared) to avoid race conditions, matching discovery-service-v2 behaviour.
 * The shared {@link KeyFactory} instance is thread-safe per the JCA specification.
 * </p>
 */
public final class CryptoService {

    private static final int RAW_ED25519_KEY_LENGTH = 32;
    private static final int PKCS8_PRIVATE_KEY_LENGTH = 48;
    private static final String ALGORITHM_ED25519 = "Ed25519";
    private static final String PEM_PUBLIC_KEY_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_PUBLIC_KEY_FOOTER = "-----END PUBLIC KEY-----";
    private static final String PEM_PRIVATE_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_PRIVATE_KEY_FOOTER = "-----END PRIVATE KEY-----";

    /** Shared KeyFactory — thread-safe per JCA spec, created once at construction. */
    private final KeyFactory ed25519KeyFactory;
    private final Logger logger;

    /**
     * Constructs a CryptoService with the given logger.
     * <p>
     * Registers the BouncyCastle provider if not already registered, then
     * initializes the Ed25519 {@link KeyFactory}. Fails fast at construction
     * if Ed25519 is unavailable.
     * </p>
     *
     * @param logger the pluggable logger for debug and error output
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if the Ed25519
     *                            algorithm is not available in the JVM
     */
    public CryptoService(Logger logger) {
        this.logger = logger;
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.info("BouncyCastle security provider registered");
        }
        try {
            this.ed25519KeyFactory = KeyFactory.getInstance(ALGORITHM_ED25519);
            logger.info("CryptoService initialized | Ed25519 KeyFactory ready");
        } catch (NoSuchAlgorithmException exception) {
            logger.error("Ed25519 algorithm is not available in this JVM — check BouncyCastle registration",
                    exception);
            throw BecknAuthException.internalError(
                    "Ed25519 algorithm not available in JVM", exception);
        }
    }

    /**
     * Generates a BLAKE2b-512 hash of the given payload string.
     * <p>
     * Uses BouncyCastle {@link Blake2bDigest} directly (same as discovery-service-v2).
     * The payload is encoded to UTF-8 bytes before hashing to ensure consistent
     * cross-platform results.
     * </p>
     *
     * @param payload the raw request body string to hash (must be the exact bytes
     *                that will be sent over the wire)
     * @return Base64-encoded 64-byte BLAKE2b-512 digest
     */
    public String generateBlake2bHash(String payload) {
        Blake2bDigest blake2bDigest = new Blake2bDigest(512);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        blake2bDigest.update(payloadBytes, 0, payloadBytes.length);
        byte[] digestBytes = new byte[64];
        blake2bDigest.doFinal(digestBytes, 0);
        String hash = Base64.getEncoder().encodeToString(digestBytes);
        logger.debug("BLAKE2b-512 hash generated | payloadBytes=" + payloadBytes.length);
        return hash;
    }

    /**
     * Signs a signing string using the Ed25519 algorithm.
     * <p>
     * A new {@link Signature} instance is created per call for thread safety.
     * The signing string is encoded to UTF-8 bytes before signing.
     * </p>
     *
     * @param signingString the exact formatted signing string to sign
     *                      (output of {@code SignatureHeaderBuilder.buildSigningString()})
     * @param privateKey    the Ed25519 private key loaded via {@link #parsePrivateKey}
     * @return Base64-encoded Ed25519 signature bytes
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if signing fails
     */
    public String signWithEd25519(String signingString, PrivateKey privateKey) {
        try {
            Signature signatureInstance = Signature.getInstance(ALGORITHM_ED25519);
            signatureInstance.initSign(privateKey);
            signatureInstance.update(signingString.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signatureInstance.sign());
            logger.debug("Ed25519 signature generated successfully");
            return signature;
        } catch (Exception exception) {
            logger.error("Ed25519 signing failed — check that the private key is valid", exception);
            throw BecknAuthException.internalError("Ed25519 signing failed", exception);
        }
    }

    /**
     * Verifies an Ed25519 signature against a signing string and public key.
     * <p>
     * A new {@link Signature} instance is created per call for thread safety.
     * Returns {@code false} for an invalid signature; throws for a JCA-level error
     * (e.g. corrupted key object), unlike discovery-service-v2 which returns {@code false}
     * for both cases.
     * </p>
     *
     * @param signingString       the exact signing string that was originally signed
     * @param signatureBase64     the Base64-encoded signature from the Authorization header
     * @param publicKey           the signer's Ed25519 public key from the registry
     * @return {@code true} if the signature is valid, {@code false} if the signature
     *         does not match (cryptographic mismatch)
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if a JCA-level error
     *                            occurs (as opposed to a simple mismatch)
     */
    public boolean verifyEd25519Signature(String signingString, String signatureBase64, PublicKey publicKey) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            Signature signatureInstance = Signature.getInstance(ALGORITHM_ED25519);
            signatureInstance.initVerify(publicKey);
            signatureInstance.update(signingString.getBytes(StandardCharsets.UTF_8));
            boolean isValid = signatureInstance.verify(signatureBytes);
            if (isValid) {
                logger.debug("Ed25519 signature verification PASSED");
            } else {
                logger.warn("Ed25519 signature verification FAILED"
                        + " | signatureBytes=" + signatureBytes.length
                        + " | mismatch: signature does not match signing string");
            }
            return isValid;
        } catch (Exception exception) {
            logger.error("Ed25519 verification encountered a JCA-level error"
                    + " — this is an internal error, not a signature mismatch", exception);
            throw BecknAuthException.internalError("Ed25519 verification error", exception);
        }
    }

    /**
     * Parses a public key string into a {@link PublicKey} object.
     * <p>
     * Supports two formats:
     * <ul>
     * <li><b>Raw 32-byte Ed25519 key</b> (Base64-encoded): wrapped into X.509 SPKI
     * format using BouncyCastle {@link SubjectPublicKeyInfo} — identical to
     * discovery-service-v2 {@code CryptoUtils.wrapRawKeyInX509()}</li>
     * <li><b>X.509 SPKI key</b> (44+ bytes decoded): passed directly to
     * {@link KeyFactory}</li>
     * </ul>
     * Both PEM-formatted (with {@code -----BEGIN PUBLIC KEY-----} headers) and
     * raw Base64 strings are accepted.
     * </p>
     *
     * @param publicKeyString the public key in PEM or raw Base64 format
     * @return the parsed {@link PublicKey} instance
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if the key
     *                            format is invalid or parsing fails
     */
    public PublicKey parsePublicKey(String publicKeyString) {
        try {
            byte[] keyBytes = decodeKeyContent(publicKeyString, PEM_PUBLIC_KEY_HEADER, PEM_PUBLIC_KEY_FOOTER);

            if (keyBytes.length == RAW_ED25519_KEY_LENGTH) {
                logger.debug("Public key is raw 32-byte Ed25519 format, wrapping in X.509 SPKI");
                keyBytes = wrapRawPublicKeyInX509Spki(keyBytes);
            } else {
                logger.debug("Public key is X.509 SPKI format | decodedBytes=" + keyBytes.length);
            }

            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            PublicKey publicKey = ed25519KeyFactory.generatePublic(keySpec);
            logger.info("Public key parsed successfully");
            return publicKey;
        } catch (BecknAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            logger.error("Failed to parse public key — check key format (raw Base64 or PEM)", exception);
            throw BecknAuthException.internalError("Failed to parse public key", exception);
        }
    }

    /**
     * Parses a private key string into a {@link PrivateKey} object.
     * <p>
     * Supports three formats:
     * <ul>
     * <li><b>PKCS#8 PEM</b> (with {@code -----BEGIN PRIVATE KEY-----} headers,
     * 48 bytes decoded): passed directly to {@link KeyFactory} via
     * {@link PKCS8EncodedKeySpec}</li>
     * <li><b>PKCS#8 raw Base64</b> (48 bytes decoded): same as above</li>
     * <li><b>Raw 32-byte Ed25519 seed</b>: handled via JDK {@link EdECPrivateKeySpec} —
     * identical to response-dispatcher {@code PrivateKeyLoader.loadPrivateKey()}</li>
     * </ul>
     * </p>
     *
     * @param privateKeyString the private key in PEM or raw Base64 format
     * @return the parsed {@link PrivateKey} instance
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if the key
     *                            format is unrecognised or parsing fails
     */
    public PrivateKey parsePrivateKey(String privateKeyString) {
        try {
            byte[] keyBytes = decodeKeyContent(privateKeyString, PEM_PRIVATE_KEY_HEADER, PEM_PRIVATE_KEY_FOOTER);

            // PKCS#8 format (48 bytes) — pass directly to KeyFactory
            if (keyBytes.length == PKCS8_PRIVATE_KEY_LENGTH) {
                logger.debug("Private key is PKCS#8 format | decodedBytes=" + keyBytes.length);
                PrivateKey privateKey = ed25519KeyFactory.generatePrivate(
                        new PKCS8EncodedKeySpec(keyBytes));
                logger.info("Private key parsed successfully (PKCS#8)");
                return privateKey;
            }

            // Raw 32-byte Ed25519 seed — use EdECPrivateKeySpec (same as response-dispatcher)
            if (keyBytes.length == RAW_ED25519_KEY_LENGTH) {
                logger.debug("Private key is raw 32-byte Ed25519 seed format, using EdECPrivateKeySpec");
                EdECPrivateKeySpec keySpec = new EdECPrivateKeySpec(
                        new NamedParameterSpec(ALGORITHM_ED25519), keyBytes);
                PrivateKey privateKey = ed25519KeyFactory.generatePrivate(keySpec);
                logger.info("Private key parsed successfully (raw seed)");
                return privateKey;
            }

            // Unrecognised length — fail with a clear message
            String errorMessage = String.format(
                    "Invalid private key length: %d bytes. Expected %d (raw Ed25519 seed) or %d (PKCS#8)",
                    keyBytes.length, RAW_ED25519_KEY_LENGTH, PKCS8_PRIVATE_KEY_LENGTH);
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);

        } catch (BecknAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            logger.error("Failed to parse private key — check key format (raw Base64 or PEM)", exception);
            throw BecknAuthException.internalError("Failed to parse private key", exception);
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    /**
     * Wraps a raw 32-byte Ed25519 public key in X.509 Subject Public Key Info (SPKI)
     * format using BouncyCastle.
     * <p>
     * This is identical to discovery-service-v2
     * {@code CryptoUtils.wrapRawKeyInX509()}.
     * </p>
     *
     * @param rawPublicKey the raw 32-byte Ed25519 public key
     * @return the X.509 SPKI-encoded key bytes (44 bytes)
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if wrapping fails
     */
    private byte[] wrapRawPublicKeyInX509Spki(byte[] rawPublicKey) {
        try {
            AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(
                    EdECObjectIdentifiers.id_Ed25519);
            SubjectPublicKeyInfo publicKeyInfo = new SubjectPublicKeyInfo(algorithmIdentifier, rawPublicKey);
            return publicKeyInfo.getEncoded();
        } catch (Exception exception) {
            logger.error("Failed to wrap raw Ed25519 public key in X.509 SPKI format", exception);
            throw BecknAuthException.internalError(
                    "Failed to wrap raw key in X.509 format", exception);
        }
    }

    /**
     * Strips PEM headers/footers and all whitespace, then Base64-decodes the result.
     *
     * @param keyString the raw or PEM-formatted key string
     * @param header    the PEM header line to strip (e.g. {@code "-----BEGIN PUBLIC KEY-----"})
     * @param footer    the PEM footer line to strip (e.g. {@code "-----END PUBLIC KEY-----"})
     * @return the decoded key bytes
     * @throws IllegalArgumentException if the resulting content is empty after stripping
     */
    private byte[] decodeKeyContent(String keyString, String header, String footer) {
        String cleanedContent = keyString
                .replace(header, "")
                .replace(footer, "")
                .replaceAll("\\s", "");

        if (cleanedContent.isEmpty()) {
            throw new IllegalArgumentException("Key content is empty after stripping PEM headers");
        }

        return Base64.getDecoder().decode(cleanedContent);
    }
}
