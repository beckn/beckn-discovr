package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.exception.PayloadParseException;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.util.FieldExtractor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Component
public class ParseStep {

    private final ObjectMapper objectMapper;

    public ParseStep(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedCatalogMessage parse(String rawMessage) {
        JsonNode root = tryParse(rawMessage)
                .orElseThrow(() -> new PayloadParseException("Invalid JSON in message"));
        CatalogContext ctx = extractContext(root);
        List<JsonNode> catalogs = extractCatalogs(root);
        if (catalogs.isEmpty()) {
            throw new PayloadParseException("No catalogs found in message");
        }
        return new ParsedCatalogMessage(root, ctx, catalogs);
    }

    public String extractCatalogIdSafe(JsonNode catalogNode) {
        return FieldExtractor.extractString(catalogNode, BecknFields.ID)
                .orElse(null);
    }

    private Optional<JsonNode> tryParse(String raw) {
        try {
            return Optional.ofNullable(objectMapper.readTree(raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private CatalogContext extractContext(JsonNode root) {
        JsonNode ctx = FieldExtractor.requireNode(root, BecknFields.CONTEXT);
        // Extract networkIds (may be string or array)
        var networkIds = Arrays.asList(FieldExtractor.extractNetworkIds(ctx));
        // Extract subscriberId — injected by Catalg API from auth; defaults to "anonymous"
        var subscriberId = FieldExtractor.extractString(ctx, "subscriberId").orElse("anonymous");
        return new CatalogContext(networkIds, subscriberId, ctx);
    }

    private List<JsonNode> extractCatalogs(JsonNode root) {
        JsonNode message = root.path(BecknFields.MESSAGE);
        JsonNode catalogs = message.isMissingNode() ? root.path(BecknFields.CATALOGS) : message.path(BecknFields.CATALOGS);
        if (catalogs.isMissingNode() || !catalogs.isArray())
            return List.of();
        return StreamSupport.stream(catalogs.spliterator(), false)
                .toList();
    }
}
