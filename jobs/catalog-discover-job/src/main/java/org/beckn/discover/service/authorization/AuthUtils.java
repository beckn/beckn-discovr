package org.beckn.discover.service.authorization;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.config.DiscoveryProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/**
 * Authorization Utility Functions
 * Handles parsing, validation logic, and error formatting
 * Aligned with Node.js authUtils.ts
 */
@Component
public class AuthUtils {

    private static final Pattern SIGNATURE_PARAM_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    // Constants extracted for better maintainability
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_X_GATEWAY_AUTHORIZATION = "X-Gateway-Authorization";
    private static final String HEADER_KEY_ID = "keyId";
    static final String HEADER_CREATED = "created";
    static final String HEADER_EXPIRES = "expires";
    static final String HEADER_SIGNATURE = "signature";
    static final String HEADER_ALGORITHM = "algorithm";
    static final String HEADER_HEADERS = "headers";

    private final DiscoveryProperties discoveryProperties;
    private String registryUrlPrefix;

    public AuthUtils(DiscoveryProperties discoveryProperties) {
        this.discoveryProperties = discoveryProperties;
    }

    /**
     * Initialize AuthUtils.
     * <p>
     * <b>Optimization:</b>
     * Pre-computes the static portion of the Registry URL (Base URL + properties)
     * to avoid repetitive string manipulation (e.g., trimming slashes) during
     * hot-path request processing.
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        DiscoveryProperties.RegistryAuthConfig config = discoveryProperties.getRegistryAuth();
        String baseUrl = config.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // Pre-compute prefix: baseUrl/
        this.registryUrlPrefix = baseUrl + "/";
    }

    /**
     * Parsed Auth Header Structure
     */
    public record ParsedAuthHeader(
            String subscriberId,
            String uniqueKeyId,
            String algorithm,
            long created,
            long expires,
            String signature) {
    }

    /**
     * Construct Registry URL based on config and signature data.
     * <p>
     * <b>Performance:</b>
     * Uses pre-computed registryUrlPrefix for O(1) string concatenation, avoiding
     * Regex or formatting overhead.
     */
    public String constructRegistryUrl(String subscriberId, String uniqueKeyId) {
        return this.registryUrlPrefix + subscriberId + "/" + discoveryProperties.getRegistryAuth().getRegistryName()
                + "/" + uniqueKeyId;
    }

    /**
     * Extract authorization header from HTTP headers.
     */
    public String extractAuthorizationHeader(HttpHeaders headers, String transactionId) {
        String authHeader = Optional.ofNullable(headers.getFirst(HEADER_AUTHORIZATION))
                .filter(h -> !h.isEmpty())
                .orElse(headers.getFirst(HEADER_X_GATEWAY_AUTHORIZATION));

        if (authHeader == null || authHeader.isEmpty()) {
            throw authError(ErrorMessages.AUTH_HEADER_MISSING, ErrorCodes.SEC_SIGNATURE_MISSING,
                    "authorization", transactionId, HttpStatus.BAD_REQUEST);
        }
        return authHeader;
    }

    /**
     * Parse signature parameters and validate format.
     */
    public ParsedAuthHeader parseAuthHeader(String authHeader, String transactionId) {
        try {
            String signaturePart;
            // Strict Parity: Enforce "Signature " prefix (case-insensitive to be robust,
            // but presence is mandatory)
            if (authHeader.length() >= 10 && authHeader.regionMatches(true, 0, "signature ", 0, 10)) {
                signaturePart = authHeader.substring(10);
            } else {
                throw authError(ErrorMessages.AUTH_INVALID_FORMAT, ErrorCodes.SEC_SIGNATURE_INVALID, "authorization",
                        transactionId, HttpStatus.BAD_REQUEST);
            }

            Map<String, String> params = new HashMap<>();
            Matcher matcher = SIGNATURE_PARAM_PATTERN.matcher(signaturePart);
            while (matcher.find()) {
                params.put(matcher.group(1), matcher.group(2));
            }

            validateRequiredParams(params, transactionId);

            // keyId format: subscriber_id|keyId|algorithm
            String fullKeyId = params.get(HEADER_KEY_ID);

            // Optimized manual split for "sub|key|algo" (max 3 parts)
            int firstPipe = fullKeyId.indexOf('|');
            int secondPipe = (firstPipe != -1) ? fullKeyId.indexOf('|', firstPipe + 1) : -1;

            if (firstPipe == -1 || secondPipe == -1) {
                throw authError(ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid keyId format",
                        ErrorCodes.SEC_SIGNATURE_INVALID,
                        "authorization/keyId",
                        transactionId, HttpStatus.BAD_REQUEST);
            }

            String subscriberId = fullKeyId.substring(0, firstPipe);
            String uniqueKeyId = fullKeyId.substring(firstPipe + 1, secondPipe);
            String algorithm = fullKeyId.substring(secondPipe + 1);

            if (subscriberId.isEmpty()) {
                throw authError(ErrorMessages.AUTH_SUBSCRIBER_NOT_FOUND, ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND,
                        "authorization/keyId",
                        transactionId, HttpStatus.BAD_REQUEST);
            }

            if (uniqueKeyId.isEmpty() || algorithm.isEmpty()) {
                throw authError(ErrorMessages.AUTH_INVALID_FORMAT + ": Invalid keyId format",
                        ErrorCodes.SEC_SIGNATURE_INVALID,
                        "authorization/keyId",
                        transactionId, HttpStatus.BAD_REQUEST);
            }

            return new ParsedAuthHeader(
                    subscriberId,
                    uniqueKeyId,
                    algorithm,
                    Long.parseLong(params.get(HEADER_CREATED)),
                    Long.parseLong(params.get(HEADER_EXPIRES)),
                    params.get(HEADER_SIGNATURE));

        } catch (ErrorResponseException e) {
            throw e;
        } catch (Exception e) {
            throw authError(ErrorMessages.AUTH_INVALID_FORMAT, ErrorCodes.INVALID_REQUEST, "authorization",
                    transactionId,
                    HttpStatus.BAD_REQUEST);
        }
    }

    // Optimization: static array to avoid allocation on every request
    private static final String[] REQUIRED_FIELDS = {
            HEADER_KEY_ID, HEADER_ALGORITHM, HEADER_CREATED,
            HEADER_EXPIRES, HEADER_HEADERS, HEADER_SIGNATURE
    };

    private void validateRequiredParams(Map<String, String> params, String transactionId) {
        for (String field : REQUIRED_FIELDS) {
            if (isBlank(params.get(field))) {
                throw authError(ErrorMessages.AUTH_PARTIAL_SIGNATURE, ErrorCodes.SEC_SIGNATURE_INVALID, "authorization",
                        transactionId, HttpStatus.BAD_REQUEST);
            }
        }
    }

    private boolean isBlank(String s) {
        // Optimization: Use Java 11+ native isBlank (avoids allocation from trim())
        return s == null || s.isBlank();
    }

    /**
     * Validate timestamp freshness.
     */
    public void validateTimestamps(ParsedAuthHeader auth, String transactionId) {
        long now = System.currentTimeMillis() / 1000;

        if (auth.created() > now) {
            throw authError(ErrorMessages.AUTH_FUTURE_CREATED, ErrorCodes.SEC_SIGNATURE_INVALID,
                    "authorization/created",
                    transactionId, HttpStatus.UNAUTHORIZED);
        }
        if (auth.expires() < now) {
            throw authError(ErrorMessages.AUTH_EXPIRED, ErrorCodes.SEC_SIGNATURE_INVALID, "authorization/expires",
                    transactionId,
                    HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Factory for Auth Errors
     */
    public ErrorResponseException authError(String message, String code, String paths, String transactionId,
            HttpStatus status) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("code", code);
        pd.setProperty("paths", paths);
        pd.setProperty("transactionId", transactionId);
        return new ErrorResponseException(status, pd, null);
    }
}
