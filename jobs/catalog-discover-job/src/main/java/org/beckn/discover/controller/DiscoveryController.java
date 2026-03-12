package org.beckn.discover.controller;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.beckn.discover.service.authorization.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring Boot REST controller for Beckn discovery API.
 *
 * <p>Entry point for {@code /beckn/discover}.</p>
 *
 * <p><b>Request Processing Pipeline:</b></p>
 * <ol>
 *   <li><b>Authorization:</b> Validates Beckn HTTP Signatures via {@link AuthorizationService}.</li>
 *   <li><b>Schema Validation:</b> Validates JSON structure via {@link DiscoveryValidationService}.</li>
 *   <li><b>Business Logic:</b> Propagates valid requests to {@link DiscoveryService}.</li>
 * </ol>
 *
 * <p>The {@code transaction_id} from the request context is stored as a servlet
 * request attribute ({@code "beckn.transactionId"}) early in the pipeline so that
 * {@link org.beckn.discover.exception.GlobalExceptionHandler} can include it in
 * NACK responses even when an exception is thrown before the request is parsed
 * into a {@link DiscoverRequest}.</p>
 */
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RestController
@RequestMapping("/beckn")
public class DiscoveryController {

    /** Request attribute key used to propagate the transaction ID to the exception handler. */
    public static final String TRANSACTION_ID_ATTR = "beckn.transactionId";

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryController.class);

    private final DiscoveryService discoveryService;
    private final ObjectMapper objectMapper;
    private final DiscoveryValidationService validationService;
    private final AuthorizationService authorizationService;

    public DiscoveryController(
            DiscoveryService discoveryService,
            ObjectMapper objectMapper,
            DiscoveryValidationService validationService,
            AuthorizationService authorizationService) {
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
        this.validationService = validationService;
        this.authorizationService = authorizationService;
    }

    /** GET endpoint for Beckn discovery. */
    @GetMapping("/discover")
    public ResponseEntity<DiscoverResponse> discover(
            @RequestBody byte[] rawBytes,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest httpRequest) throws Exception {
        return handleDiscoverRequest(rawBytes, headers, httpRequest);
    }

    /** POST endpoint for Beckn discovery. */
    @PostMapping("/discover")
    public ResponseEntity<DiscoverResponse> discoverPost(
            @RequestBody byte[] rawBytes,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest httpRequest) throws Exception {
        return handleDiscoverRequest(rawBytes, headers, httpRequest);
    }

    /** Shared pipeline: authorize → validate → process. */
    private ResponseEntity<DiscoverResponse> handleDiscoverRequest(
            byte[] rawBytes, HttpHeaders headers, HttpServletRequest httpRequest) throws Exception {

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        JsonNode requestNode = objectMapper.readTree(rawBody);

        // Store transactionId early so GlobalExceptionHandler can include it in NACKs
        // even when an exception is thrown before full request parsing.
        JsonNode txnNode = requestNode.path("context").path("transaction_id");
        if (txnNode.isTextual() && !txnNode.asText().isBlank()) {
            httpRequest.setAttribute(TRANSACTION_ID_ATTR, txnNode.asText());
        }

        authorizationService.authorizeRequest(rawBody, requestNode, headers);
        validateSchema(requestNode);

        DiscoverRequest request = objectMapper.convertValue(requestNode, DiscoverRequest.class);
        DiscoverResponse result = discoveryService.processDiscoveryRequest(request);
        return ResponseEntity.ok(result);
    }

    private void validateSchema(JsonNode requestNode) {
        logger.info("Validating request against schema");
        DiscoveryValidationService.ValidationResult result = validationService.validateDiscoverRequest(requestNode);
        if (!result.isValid()) {
            String paths = result.getPaths().isEmpty() ? "root" : String.join(", ", result.getPaths());
            String msg = "Schema validation failed: " + String.join("; ", result.getErrors()) + " (paths: " + paths + ")";
            logger.warn(msg);
            throw new IllegalArgumentException(msg);
        }
        logger.info("Schema validation passed");
    }
}
