package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.exception.ValidationException;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.beckn.catalogpublish.validation.CatalogMessageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ValidateStep {

    private static final Logger log = LoggerFactory.getLogger(ValidateStep.class);
    private static final int SNIPPET_LEN = 500;

    private final CatalogMessageValidator validator;

    public ValidateStep(CatalogMessageValidator validator) {
        this.validator = validator;
    }

    /**
     * Validates the message subtree against CatalogPublishAction schema.
     *
     * <p>The schema expects {@code {"catalogs":[...]}} at root level.
     * Incoming payloads may be either:
     * <ul>
     *   <li>Direct publish: {@code {"context":{...},"message":{"catalogs":[...]}}}</li>
     *   <li>on_discover callback: same envelope format</li>
     * </ul>
     * In both cases, we validate the {@code message} node (which contains {@code catalogs}),
     * not the full root (which also contains {@code context}).
     */
    public void validate(ParsedCatalogMessage parsed) {
        JsonNode root = parsed.rootNode();
        JsonNode messageNode = root.path(BecknFields.MESSAGE);
        // If the root has a "message" wrapper, validate that; otherwise validate root directly
        // (handles both {context,message} envelope and raw {catalogs} payloads)
        JsonNode nodeToValidate = messageNode.isMissingNode() ? root : messageNode;
        try {
            validator.validate(nodeToValidate);
        } catch (ValidationException e) {
            String rootText = root.toString();
            String snippet = rootText.length() > SNIPPET_LEN
                    ? rootText.substring(0, SNIPPET_LEN) + "…"
                    : rootText;
            log.warn("event={} errors={} bodySnippet={}", LogEvent.VALIDATE_FAILED, e.getErrors(), ErrorSanitizer.sanitize(snippet));
            throw e;
        }
    }
}
