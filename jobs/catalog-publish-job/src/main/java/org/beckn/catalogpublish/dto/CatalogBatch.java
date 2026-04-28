package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.model.Item;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Result of PersistenceStep for one catalog.
 * <p>
 * {@code payloadNodes} carries the already-parsed {@link JsonNode} for each saved resource,
 * keyed by resource ID. Passing pre-parsed nodes avoids re-parsing the payload JSON strings
 * in the post-commit Kafka publishing path.
 */
public record CatalogBatch(
    String catalogId,
    CatalogContext context,
    @Nullable String schemaType,
    CatalogOperation operation,
    List<Item> savedResources,
    List<ProcessingError> errors,
    Map<String, JsonNode> payloadNodes,
    boolean fullReplace
) {
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasResources() {
        return !savedResources.isEmpty();
    }

    public int savedCount() {
        return savedResources.size();
    }

    public int errorCount() {
        return errors.size();
    }
}
