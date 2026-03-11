package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Parse result from ParseStep.
 */
public record ParsedCatalogMessage(
    JsonNode rootNode,
    CatalogContext context,
    List<JsonNode> catalogs
) {}
