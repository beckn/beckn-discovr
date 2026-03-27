package org.beckn.auth.verification;

import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.LoggerFactory;
import org.beckn.auth.model.ParsedAuthHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthHeaderParserTest {

    private AuthHeaderParser parser;

    @BeforeEach
    void setUp() {
        parser = new AuthHeaderParser(LoggerFactory.createLogger(AuthHeaderParser.class));
    }

    // ─── Helper to build a minimal valid Signature header ───────────────────────

    private String validHeader(String keyId, String algorithm, long created, long expires) {
        return "Signature keyId=\"" + keyId + "\","
                + "algorithm=\"" + algorithm + "\","
                + "created=\"" + created + "\","
                + "expires=\"" + expires + "\","
                + "headers=\"(created) (expires) digest\","
                + "signature=\"dGVzdA==\"";
    }

    @Nested
    @DisplayName("Parsing — Happy Path")
    class ParsingTests {

        @Test
        @DisplayName("Should parse a valid Signature header into all fields correctly")
        void parseAuthorizationHeader_Valid() {
            String header = validHeader("example-bap.com|key-1|ed25519", "ed25519", 1700000000L, 1700003600L);

            ParsedAuthHeader parsed = parser.parseAuthorizationHeader(header);

            assertThat(parsed.keyId()).isEqualTo("example-bap.com|key-1|ed25519");
            assertThat(parsed.subscriberId()).isEqualTo("example-bap.com");
            assertThat(parsed.uniqueKeyId()).isEqualTo("key-1");
            assertThat(parsed.algorithm()).isEqualTo("ed25519");
            assertThat(parsed.created()).isEqualTo(1700000000L);
            assertThat(parsed.expires()).isEqualTo(1700003600L);
            assertThat(parsed.headers()).isEqualTo("(created) (expires) digest");
            assertThat(parsed.signature()).isEqualTo("dGVzdA==");
        }

        @Test
        @DisplayName("Prefix matching should be case-insensitive for 'Signature '")
        void parseAuthorizationHeader_CaseInsensitivePrefix() {
            String header = "signature keyId=\"bap.com|k|ed25519\",algorithm=\"ed25519\","
                    + "created=\"1700000000\",expires=\"1700003600\","
                    + "headers=\"(created) (expires) digest\",signature=\"dGVzdA==\"";
            // Should not throw
            assertThatCode(() -> parser.parseAuthorizationHeader(header)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Parsing — Missing or Malformed Input")
    class ParsingErrorTests {

        @Test
        @DisplayName("Null header throws SEC_SIGNATURE_MISSING")
        void parseAuthorizationHeader_Null() {
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(null))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Missing Authorization");
        }

        @Test
        @DisplayName("Blank header throws SEC_SIGNATURE_MISSING")
        void parseAuthorizationHeader_Blank() {
            assertThatThrownBy(() -> parser.parseAuthorizationHeader("   "))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Missing Authorization");
        }

        @Test
        @DisplayName("Wrong prefix ('Bearer ...') throws SEC_SIGNATURE_INVALID")
        void parseAuthorizationHeader_BearerPrefix() {
            assertThatThrownBy(() -> parser.parseAuthorizationHeader("Bearer some-token"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Invalid Beckn HTTP Signature format");
        }

        @Test
        @DisplayName("Missing required fields (only keyId + algorithm) throws SEC_SIGNATURE_INVALID")
        void parseAuthorizationHeader_MissingRequiredFields() {
            String header = "Signature keyId=\"example-bap.com|key-1|ed25519\",algorithm=\"ed25519\"";
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Signature incomplete");
        }

        @Test
        @DisplayName("KeyId with only two parts (missing algorithm) throws SEC_SIGNATURE_INVALID")
        void parseAuthorizationHeader_KeyIdTwoParts() {
            String header = validHeader("example-bap.com|key-1", "ed25519", 1700000000L, 1700003600L);
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Invalid Beckn HTTP Signature format");
        }

        @Test
        @DisplayName("Empty subscriberId in keyId throws SEC_SUBSCRIBER_NOT_FOUND")
        void parseAuthorizationHeader_EmptySubscriberId() {
            String header = validHeader("|key-1|ed25519", "ed25519", 1700000000L, 1700003600L);
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Subscriber ID missing");
        }

        @Test
        @DisplayName("Non-numeric created timestamp throws SEC_SIGNATURE_INVALID")
        void parseAuthorizationHeader_NonNumericCreated() {
            String header = "Signature keyId=\"bap.com|k|ed25519\",algorithm=\"ed25519\","
                    + "created=\"not-a-number\",expires=\"1700003600\","
                    + "headers=\"(created) (expires) digest\",signature=\"dGVzdA==\"";
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Invalid created timestamp");
        }
    }

    @Nested
    @DisplayName("Algorithm Validation")
    class AlgorithmValidationTests {

        @Test
        @DisplayName("Algorithm 'ed25519' (lowercase) passes validateAlgorithm")
        void validateAlgorithm_Valid_Lowercase() {
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519", 1L, 2L, "h", "s");
            assertThatCode(() -> parser.validateAlgorithm(parsed)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Algorithm 'ED25519' (uppercase) passes validateAlgorithm — case-insensitive")
        void validateAlgorithm_Valid_Uppercase() {
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ED25519", 1L, 2L, "h", "s");
            assertThatCode(() -> parser.validateAlgorithm(parsed)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Wrong keyId algorithm suffix ('rsa') throws SEC_SIGNATURE_INVALID during parse")
        void parseAuthorizationHeader_WrongKeyIdAlgorithm() {
            String header = validHeader("example-bap.com|key-1|rsa", "ed25519", 1700000000L, 1700003600L);
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("expected ed25519");
        }

        @Test
        @DisplayName("Wrong header-level algorithm ('rsa-sha256') throws SEC_SIGNATURE_INVALID during parse")
        void parseAuthorizationHeader_WrongHeaderAlgorithm() {
            String header = validHeader("example-bap.com|key-1|ed25519", "rsa-sha256", 1700000000L, 1700003600L);
            assertThatThrownBy(() -> parser.parseAuthorizationHeader(header))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("expected ed25519");
        }

        @Test
        @DisplayName("Wrong algorithm in parsed record throws SEC_SIGNATURE_INVALID from validateAlgorithm")
        void validateAlgorithm_WrongAlgorithm() {
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "rsa", 1L, 2L, "h", "s");
            assertThatThrownBy(() -> parser.validateAlgorithm(parsed))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("expected ed25519");
        }
    }

    @Nested
    @DisplayName("Timestamp Validation")
    class TimestampValidationTests {

        @Test
        @DisplayName("Valid timestamps (created in past, expires in future) pass")
        void validateTimestamps_Valid() {
            long now = Instant.now().getEpochSecond();
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519",
                    now - 10, now + 3600, "h", "s");
            assertThatCode(() -> parser.validateTimestamps(parsed, 30)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Created slightly in the future but within skew passes")
        void validateTimestamps_FutureWithinSkew() {
            long now = Instant.now().getEpochSecond();
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519",
                    now + 15, now + 3600, "h", "s");
            assertThatCode(() -> parser.validateTimestamps(parsed, 30)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Created far in the future (beyond skew) throws SEC_SIGNATURE_INVALID")
        void validateTimestamps_CreatedTooFarInFuture() {
            long now = Instant.now().getEpochSecond();
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519",
                    now + 60, now + 3600, "h", "s");
            assertThatThrownBy(() -> parser.validateTimestamps(parsed, 30))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Signature created in the future");
        }

        @Test
        @DisplayName("Expires in the past (beyond skew) throws SEC_SIGNATURE_INVALID")
        void validateTimestamps_Expired() {
            long now = Instant.now().getEpochSecond();
            // Expires 40 seconds ago, worse than the 30s allowed skew
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519",
                    now - 3600, now - 40, "h", "s");
            assertThatThrownBy(() -> parser.validateTimestamps(parsed, 30))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Signature has expired");
        }

        @Test
        @DisplayName("Expires slightly in the past but within skew passes")
        void validateTimestamps_ExpiredWithinSkew() {
            long now = Instant.now().getEpochSecond();
            // Expires 15 seconds ago, within the 30s skew
            ParsedAuthHeader parsed = new ParsedAuthHeader("k", "sub", "key", "ed25519",
                    now - 3600, now - 15, "h", "s");
            assertThatCode(() -> parser.validateTimestamps(parsed, 30)).doesNotThrowAnyException();
        }
    }
}
