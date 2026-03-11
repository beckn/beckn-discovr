package org.beckn.catalogpublish.indexing.failure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EsFailureMessage(
        String itemId,
        String bppId,
        String indexKey,
        String payload,
        String errorReason,
        Instant failedAt,
        int attempt) {

    public EsFailureMessage withNextAttempt() {
        return new EsFailureMessage(itemId, bppId, indexKey, payload, errorReason, Instant.now(), attempt + 1);
    }
}
