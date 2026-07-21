package org.beckn.crawler.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 integrity check for the DeDi digest chain (design doc §5.7).
 *
 * <p>A file never carries its own digest — the expected digest always comes from the parent
 * file. Digests are written {@code "sha-256:<lowercase-hex>"} (hyphen). We compute over the
 * exact response bytes and compare case-insensitively.
 */
public final class DigestUtil {

    public static final String PREFIX = "sha-256:";

    private DigestUtil() {}

    /** {@code sha-256:<hex>} of the given bytes. */
    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never happens on a JVM
        }
    }

    /**
     * True when {@code bytes} hash to {@code expectedDigest}. The expected value must carry the
     * {@code sha-256:} prefix (as it appears in the parent DeDi file); comparison is
     * case-insensitive. A null/blank expected digest is never a match.
     */
    public static boolean matches(byte[] bytes, String expectedDigest) {
        if (expectedDigest == null || expectedDigest.isBlank()) return false;
        String expected = expectedDigest.trim();
        if (!expected.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return false;
        return sha256(bytes).equalsIgnoreCase(expected);
    }
}
