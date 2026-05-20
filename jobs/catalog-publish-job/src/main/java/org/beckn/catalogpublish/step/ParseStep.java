package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.exception.PayloadParseException;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.util.ContextNormalizer;
import org.beckn.catalogpublish.util.FieldExtractor;
import org.springframework.stereotype.Component;

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
                .orElse("unknown");
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
        ContextNormalizer.normalize(ctx);  // V1.0 snake_case → V2.0 camelCase
        // bppId / bppUri are optional on context. The authoritative source is the
        // catalog object itself (read during PersistenceStep). Absent values are
        // tolerated and flow through as null — the item row's bpp_id / bpp_uri
        // columns are nullable (see V13__item_pk_id_only.sql).
        String bppId = FieldExtractor.extractString(ctx, BecknFields.BPP_ID).orElse(null);
        String bppUri = FieldExtractor.extractString(ctx, BecknFields.BPP_URI).orElse(null);
        String[] netIds = FieldExtractor.extractNetworkIds(ctx);
        return new CatalogContext(bppId, bppUri, netIds, ctx);
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
