package org.beckn.catalogpublish.indexing.failure;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EsFailureMessage(
        @JsonAlias("itemId") String resourceId,
        String catalogId,
        String indexKey,
        String payload,
        String errorReason,
        Instant failedAt,
        int attempt) {

    public EsFailureMessage withNextAttempt() {
        return new EsFailureMessage(resourceId, catalogId, indexKey, payload, errorReason, Instant.now(), attempt + 1);
    }
}
