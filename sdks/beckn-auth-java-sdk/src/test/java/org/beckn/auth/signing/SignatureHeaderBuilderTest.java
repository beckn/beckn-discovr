package org.beckn.auth.signing;

import org.beckn.auth.logging.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureHeaderBuilderTest {

    private SignatureHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SignatureHeaderBuilder(LoggerFactory.createLogger(SignatureHeaderBuilder.class));
    }

    @Test
    @DisplayName("Signing string matches exact Beckn specification format")
    void buildSigningString_ExactFormat() {
        long created = 1600000000L;
        long expires = 1600003600L;
        String digest = "dummy-digest";

        String expected = "(created): 1600000000\n(expires): 1600003600\ndigest: BLAKE-512=dummy-digest";

        assertThat(builder.buildSigningString(created, expires, digest)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Authorization header matches exact Beckn specification format")
    void buildAuthorizationHeader_ExactFormat() {
        String expected = "Signature keyId=\"bap.com|key-1|ed25519\","
                + "algorithm=\"ed25519\","
                + "created=\"1600000000\","
                + "expires=\"1600003600\","
                + "headers=\"(created) (expires) digest\","
                + "signature=\"test-signature==\"";

        String actual = builder.buildAuthorizationHeader("bap.com", "key-1", 1600000000L, 1600003600L, "test-signature==");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Header algorithm is always 'ed25519' regardless of input")
    void buildAuthorizationHeader_AlgorithmIsAlwaysEd25519() {
        String header = builder.buildAuthorizationHeader("bap.com", "k", 0L, 1L, "sig");
        assertThat(header).contains("algorithm=\"ed25519\"");
        assertThat(header).contains("|ed25519\""); // in keyId too
    }

    @Test
    @DisplayName("KeyId is composed as subscriberId|uniqueKeyId|ed25519")
    void buildAuthorizationHeader_KeyIdStructure() {
        String header = builder.buildAuthorizationHeader("my-subscriber.com", "uuid-1234", 0L, 1L, "sig");
        assertThat(header).contains("keyId=\"my-subscriber.com|uuid-1234|ed25519\"");
    }

    @Test
    @DisplayName("Signing string uses newline separators (not system line separator)")
    void buildSigningString_UsesNewlineNotSystemLineSeparator() {
        String result = builder.buildSigningString(100L, 200L, "abc");
        // Must use literal \n not \r\n
        assertThat(result).doesNotContain("\r\n");
        assertThat(result).contains("\n");
    }

    @Test
    @DisplayName("Signing string digest field includes 'BLAKE-512=' prefix")
    void buildSigningString_DigestPrefix() {
        String result = builder.buildSigningString(1L, 2L, "my-digest-value");
        assertThat(result).contains("digest: BLAKE-512=my-digest-value");
    }

    @Test
    @DisplayName("Header with zero timestamps still produces valid output")
    void buildAuthorizationHeader_ZeroTimestamps() {
        String header = builder.buildAuthorizationHeader("sub", "key", 0L, 0L, "sig");
        assertThat(header).contains("created=\"0\"");
        assertThat(header).contains("expires=\"0\"");
    }

    @Test
    @DisplayName("Signing string roundtrip — same input always produces same output")
    void buildSigningString_Deterministic() {
        String r1 = builder.buildSigningString(123L, 456L, "hash");
        String r2 = builder.buildSigningString(123L, 456L, "hash");
        assertThat(r1).isEqualTo(r2);
    }
}
