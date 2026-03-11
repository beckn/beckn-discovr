package org.beckn.catalogpublish.dto;

import java.util.List;

/**
 * Result of a full publish pipeline run, carrying both the already-parsed
 * context (so callers never need to re-parse the raw message) and the
 * per-catalog processing results.
 */
public record PublishOutcome(CatalogContext context, List<ProcessingResult> results) {}
