package org.beckn.auth.verification;

import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.Logger;
import org.beckn.auth.model.ParsedAuthHeader;
import org.beckn.auth.util.ErrorCodes;
import org.beckn.auth.util.ErrorMessages;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates Beckn HTTP Signature Authorization headers.
 * <p>
 * Responsible for four sequential validation steps:
 * <ol>
 * <li>Extracting key-value parameters from the {@code Signature ...} header</li>
 * <li>Validating all required fields are present and non-blank</li>
 * <li>Parsing and validating the {@code keyId} into its three pipe-separated
 * components: {@code subscriberId|uniqueKeyId|algorithm}</li>
 * <li>Validating both the keyId algorithm suffix and the header-level
 * {@code algorithm=} parameter are {@code ed25519}</li>
 * </ol>
 * </p>
 *
 * <h3>Expected Header Format (from SiginingDOC.md)</h3>
 * <pre>
 * Signature keyId="{subscriberId}|{uniqueKeyId}|ed25519",
 *           algorithm="ed25519",
 *           created="{unix_ts}",expires="{unix_ts}",
 *           headers="(created) (expires) digest",
 *           signature="{base64_signature}"
 * </pre>
 */
public final class AuthHeaderParser {

    private static final String SIGNATURE_PREFIX = "Signature ";
    private static final int SIGNATURE_PREFIX_LENGTH = SIGNATURE_PREFIX.length();
    private static final int KEY_ID_PARTS_COUNT = 3;
    private static final String EXPECTED_ALGORITHM = "ed25519";

    /**
     * Pre-compiled regex that extracts {@code key="value"} pairs from the
     * Signature parameter string. Handles both spaced and non-spaced formats.
     */
    private static final Pattern PARAM_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    /**
     * All six fields required in a valid Beckn Signature header.
     * Validated in order: keyId, algorithm, created, expires, headers, signature.
     */
    private static final String[] REQUIRED_FIELDS = {
            "keyId", "algorithm", "created", "expires", "headers", "signature"
    };

    private final Logger logger;

    /**
     * Constructs an AuthHeaderParser with the given logger.
     *
     * @param logger the pluggable logger for debug and error output
     */
    public AuthHeaderParser(Logger logger) {
        this.logger = logger;
    }

    /**
     * Parses a Beckn HTTP Signature Authorization header into a structured record.
     * <p>
     * Validates presence, format, required fields, keyId structure, and algorithm
     * consistency inline. Algorithm value validation ({@code ed25519}) is done here
     * during parsing, matching the discovery-service-v2 inline approach.
     * </p>
     *
     * @param authorizationHeader the full {@code Authorization} or
     *                            {@code X-Gateway-Authorization} header value
     * @return a fully validated {@link ParsedAuthHeader} record
     * @throws BecknAuthException with {@code SEC_SIGNATURE_MISSING} (400) if header is null/blank
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if format or fields are invalid
     * @throws BecknAuthException with {@code SEC_SUBSCRIBER_NOT_FOUND} (400) if subscriberId is blank
     */
    public ParsedAuthHeader parseAuthorizationHeader(String authorizationHeader) {
        logger.debug("Parsing authorization header");
        validateHeaderPresent(authorizationHeader);
        validateSignaturePrefix(authorizationHeader);

        Map<String, String> headerParams = extractParameters(authorizationHeader);
        validateRequiredFieldsPresent(headerParams);

        ParsedAuthHeader parsedHeader = buildParsedHeader(headerParams);
        logger.info("Authorization header parsed successfully"
                + " | subscriber=" + parsedHeader.subscriberId()
                + " | uniqueKeyId=" + parsedHeader.uniqueKeyId());
        return parsedHeader;
    }

    /**
     * Validates that the algorithm in the parsed header is {@code ed25519}
     * (case-insensitive). This is a post-parse guard on the keyId algorithm field.
     *
     * @param parsedHeader the parsed authorization header
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if algorithm is wrong
     */
    public void validateAlgorithm(ParsedAuthHeader parsedHeader) {
        if (!EXPECTED_ALGORITHM.equalsIgnoreCase(parsedHeader.algorithm().trim())) {
            logger.error("Algorithm validation failed | expected=ed25519"
                    + " | received=" + parsedHeader.algorithm()
                    + " | subscriber=" + parsedHeader.subscriberId());
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid algorithm in keyId or header, expected ed25519",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization");
        }
        logger.debug("Algorithm validated: " + EXPECTED_ALGORITHM);
    }

    /**
     * Validates that the {@code created} and {@code expires} timestamps are within
     * the allowed clock skew window relative to the current time.
     *
     * @param parsedHeader the parsed authorization header
     * @param allowedSkewSeconds allowed clock skew tolerance in seconds (typically 30)
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (401) if {@code created}
     *                            is too far in the future (replay/clock attack)
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (401) if {@code expires}
     *                            is in the past (signature expired)
     */
    public void validateTimestamps(ParsedAuthHeader parsedHeader, long allowedSkewSeconds) {
        long nowSeconds = Instant.now().getEpochSecond();

        if (parsedHeader.created() > nowSeconds + allowedSkewSeconds) {
            long deltaSeconds = parsedHeader.created() - nowSeconds;
            logger.error("Timestamp validation failed: created is in the future"
                    + " | created=" + parsedHeader.created()
                    + " | now=" + nowSeconds
                    + " | delta=" + deltaSeconds + "s"
                    + " | allowedSkew=" + allowedSkewSeconds + "s"
                    + " | subscriber=" + parsedHeader.subscriberId());
            throw BecknAuthException.timestampExpired(
                    ErrorMessages.AUTH_FUTURE_CREATED, "authorization/created");
        }

        if (parsedHeader.expires() < nowSeconds - allowedSkewSeconds) {
            long deltaSeconds = nowSeconds - parsedHeader.expires();
            logger.error("Timestamp validation failed: signature has expired"
                    + " | expires=" + parsedHeader.expires()
                    + " | now=" + nowSeconds
                    + " | expiredAgo=" + deltaSeconds + "s"
                    + " | allowedSkew=" + allowedSkewSeconds + "s"
                    + " | subscriber=" + parsedHeader.subscriberId());
            throw BecknAuthException.timestampExpired(
                    ErrorMessages.AUTH_EXPIRED, "authorization/expires");
        }

        logger.debug("Timestamps validated"
                + " | created=" + parsedHeader.created()
                + " | expires=" + parsedHeader.expires()
                + " | now=" + nowSeconds);
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    /**
     * Checks that the Authorization header value is not null or blank.
     *
     * @throws BecknAuthException with {@code SEC_SIGNATURE_MISSING} (400) if absent
     */
    private void validateHeaderPresent(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            logger.error("Authorization header is missing or empty");
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_HEADER_MISSING, ErrorCodes.SEC_SIGNATURE_MISSING);
        }
    }

    /**
     * Checks that the header value begins with {@code "Signature "} (case-insensitive).
     *
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if prefix is absent
     */
    private void validateSignaturePrefix(String authorizationHeader) {
        if (!authorizationHeader.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX_LENGTH)) {
            logger.error("Authorization header is missing the required 'Signature ' prefix"
                    + " | received=" + truncate(authorizationHeader, 80));
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT, ErrorCodes.SEC_SIGNATURE_INVALID);
        }
    }

    /**
     * Extracts all {@code key="value"} pairs from the parameter portion of the header
     * using the pre-compiled {@link #PARAM_PATTERN} regex.
     *
     * @param authorizationHeader the full header value
     * @return map of parameter name to value (unquoted)
     */
    private Map<String, String> extractParameters(String authorizationHeader) {
        Map<String, String> headerParams = new HashMap<>();
        String signatureParamString = authorizationHeader.substring(SIGNATURE_PREFIX_LENGTH);
        Matcher matcher = PARAM_PATTERN.matcher(signatureParamString);

        while (matcher.find()) {
            headerParams.put(matcher.group(1), matcher.group(2));
        }

        logger.debug("Extracted " + headerParams.size() + " parameters from header");
        return headerParams;
    }

    /**
     * Checks that all six required Signature fields are present and non-blank.
     *
     * @param headerParams the extracted parameter map
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if any field is missing
     */
    private void validateRequiredFieldsPresent(Map<String, String> headerParams) {
        for (String requiredField : REQUIRED_FIELDS) {
            String value = headerParams.get(requiredField);
            if (value == null || value.isBlank()) {
                logger.error("Required field missing from Signature header | field=" + requiredField);
                throw BecknAuthException.invalidHeader(
                        ErrorMessages.AUTH_PARTIAL_SIGNATURE + ": Missing field '" + requiredField + "'",
                        ErrorCodes.SEC_SIGNATURE_INVALID);
            }
        }
    }

    /**
     * Constructs a {@link ParsedAuthHeader} from the extracted parameter map.
     * Validates keyId structure and both algorithm fields inline.
     *
     * @param headerParams the validated parameter map
     * @return a fully populated {@link ParsedAuthHeader}
     */
    private ParsedAuthHeader buildParsedHeader(Map<String, String> headerParams) {
        String fullKeyId = headerParams.get("keyId");
        String[] keyIdParts = splitAndValidateKeyId(fullKeyId);

        String subscriberId = keyIdParts[0];
        String uniqueKeyId = keyIdParts[1];
        String keyIdAlgorithm = keyIdParts[2];
        String headerAlgorithm = headerParams.get("algorithm");

        validateKeyIdComponents(subscriberId, uniqueKeyId, keyIdAlgorithm);

        // Validate header-level algorithm param — must also equal ed25519
        // Matches discovery-service-v2 dual check (keyId algo + header param)
        if (headerAlgorithm == null || !EXPECTED_ALGORITHM.equalsIgnoreCase(headerAlgorithm.trim())) {
            logger.error("Invalid header-level algorithm parameter"
                    + " | expected=ed25519 | received=" + headerAlgorithm
                    + " | subscriberId=" + subscriberId);
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid header algorithm, expected ed25519",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization");
        }

        long created = parseTimestamp(headerParams.get("created"), "created");
        long expires = parseTimestamp(headerParams.get("expires"), "expires");

        return new ParsedAuthHeader(
                fullKeyId, subscriberId, uniqueKeyId, keyIdAlgorithm,
                created, expires,
                headerParams.get("headers"), headerParams.get("signature"));
    }

    /**
     * Splits the keyId string into exactly three parts using {@code indexOf('|')}.
     * Uses manual index-based splitting (avoids regex overhead on the hot path),
     * matching the discovery-service-v2 approach.
     *
     * @param fullKeyId the full keyId string, e.g. {@code "example-bap.com|uuid|ed25519"}
     * @return string array of [subscriberId, uniqueKeyId, algorithm]
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if format is wrong
     */
    private String[] splitAndValidateKeyId(String fullKeyId) {
        int firstPipe = fullKeyId.indexOf('|');
        int secondPipe = (firstPipe != -1) ? fullKeyId.indexOf('|', firstPipe + 1) : -1;

        if (firstPipe == -1 || secondPipe == -1) {
            logger.error("Invalid keyId format: expected 3 pipe-separated parts"
                    + " | keyId=" + fullKeyId);
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT
                            + ": Invalid keyId format, expected subscriberId|uniqueKeyId|algorithm",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization/keyId");
        }

        return new String[]{
                fullKeyId.substring(0, firstPipe),
                fullKeyId.substring(firstPipe + 1, secondPipe),
                fullKeyId.substring(secondPipe + 1)
        };
    }

    /**
     * Validates each component of the parsed keyId for expected values.
     *
     * @param subscriberId   the subscriber ID (must not be blank)
     * @param uniqueKeyId    the unique key ID (must not be blank)
     * @param keyIdAlgorithm the algorithm suffix from keyId (must equal {@code ed25519})
     * @throws BecknAuthException with {@code SEC_SUBSCRIBER_NOT_FOUND} (400) if subscriberId is blank
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if uniqueKeyId is blank
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if algorithm is wrong
     */
    private void validateKeyIdComponents(String subscriberId, String uniqueKeyId, String keyIdAlgorithm) {
        if (subscriberId.isBlank()) {
            logger.error("Empty subscriberId in keyId | subscriberId='" + subscriberId + "'");
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_SUBSCRIBER_NOT_FOUND,
                    ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND,
                    "authorization/keyId");
        }
        if (uniqueKeyId.isBlank()) {
            logger.error("Empty uniqueKeyId in keyId | uniqueKeyId='" + uniqueKeyId + "'");
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid keyId format",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization/keyId");
        }
        if (!EXPECTED_ALGORITHM.equalsIgnoreCase(keyIdAlgorithm.trim())) {
            logger.error("Invalid algorithm in keyId"
                    + " | expected=ed25519 | received=" + keyIdAlgorithm
                    + " | subscriberId=" + subscriberId);
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid algorithm in keyId, expected ed25519",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization/keyId");
        }
    }

    /**
     * Parses a timestamp string value to a {@code long} epoch second.
     *
     * @param value     the raw string value from the header parameter
     * @param fieldName the field name ({@code "created"} or {@code "expires"}) for error context
     * @return the parsed Unix epoch second
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400) if not a valid long
     */
    private long parseTimestamp(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            logger.error("Invalid " + fieldName + " timestamp: not a valid integer"
                    + " | field=" + fieldName + " | value=" + value);
            throw BecknAuthException.invalidHeader(
                    ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid " + fieldName + " timestamp",
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization/" + fieldName);
        }
    }

    /**
     * Truncates a string to {@code maxLength} characters for safe error logging,
     * appending {@code "..."} if truncated.
     *
     * @param value     the string to truncate
     * @param maxLength the maximum number of characters to include
     * @return the truncated string
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) return "null";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
