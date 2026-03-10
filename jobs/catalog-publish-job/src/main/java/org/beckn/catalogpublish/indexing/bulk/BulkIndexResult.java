package org.beckn.catalogpublish.indexing.bulk;

import java.util.List;

public record BulkIndexResult(List<String> succeeded, List<FailedDoc> failed) {

    public record FailedDoc(String itemId, String bppId, String reason) {}

    public boolean hasFailures() { return !failed.isEmpty(); }

    public static BulkIndexResult allFailed(List<FailedDoc> docs) {
        return new BulkIndexResult(List.of(), docs);
    }
}
