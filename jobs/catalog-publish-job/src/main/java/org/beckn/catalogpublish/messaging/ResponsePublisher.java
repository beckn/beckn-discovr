package org.beckn.catalogpublish.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.dto.ProcessingResult;

import java.util.List;

public interface ResponsePublisher {

    /**
     * Publishes a response event for the processed catalogs.
     *
     * @param contextNode the already-parsed {@code context} node from the inbound message —
     *                    avoids re-parsing the raw string just to extract the context
     * @param results     per-catalog processing outcomes
     */
    void publishResponse(JsonNode contextNode, List<ProcessingResult> results);
}
