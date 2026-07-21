package org.beckn.crawler.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the SHA-256 digest chain check (design doc §5.7). */
class DigestUtilTest {

    private static final byte[] BYTES = "hello dedi".getBytes(StandardCharsets.UTF_8);

    @Test
    void sha256_isPrefixedLowercaseHex() {
        String digest = DigestUtil.sha256(BYTES);
        assertThat(digest).startsWith("sha-256:");
        String hex = digest.substring(DigestUtil.PREFIX.length());
        assertThat(hex).hasSize(64).isLowerCase().matches("[0-9a-f]+");
    }

    @Test
    void sha256_isStableForSameInput() {
        assertThat(DigestUtil.sha256(BYTES)).isEqualTo(DigestUtil.sha256(BYTES));
    }

    @Test
    void matches_trueForCorrectDigest() {
        assertThat(DigestUtil.matches(BYTES, DigestUtil.sha256(BYTES))).isTrue();
    }

    @Test
    void matches_isCaseInsensitiveOnHex() {
        String upper = DigestUtil.sha256(BYTES).toUpperCase();
        assertThat(DigestUtil.matches(BYTES, upper)).isTrue();
    }

    @Test
    void matches_toleratesSurroundingWhitespace() {
        assertThat(DigestUtil.matches(BYTES, "  " + DigestUtil.sha256(BYTES) + "  ")).isTrue();
    }

    @Test
    void matches_falseForWrongBytes() {
        assertThat(DigestUtil.matches("tampered".getBytes(StandardCharsets.UTF_8), DigestUtil.sha256(BYTES)))
                .isFalse();
    }

    @Test
    void matches_falseWhenPrefixMissing() {
        String hexOnly = DigestUtil.sha256(BYTES).substring(DigestUtil.PREFIX.length());
        assertThat(DigestUtil.matches(BYTES, hexOnly)).isFalse();
    }

    @Test
    void matches_falseForNullOrBlank() {
        assertThat(DigestUtil.matches(BYTES, null)).isFalse();
        assertThat(DigestUtil.matches(BYTES, "")).isFalse();
        assertThat(DigestUtil.matches(BYTES, "   ")).isFalse();
    }
}
