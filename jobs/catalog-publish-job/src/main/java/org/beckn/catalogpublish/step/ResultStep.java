package org.beckn.catalogpublish.step;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.exception.CatalogPublishException;
import org.springframework.stereotype.Component;

@Component
public class ResultStep {

    public ProcessingResult buildResult(CatalogBatch batch) {
        if (batch.savedItems().isEmpty() && batch.hasErrors()) {
            return ProcessingResult.rejected(
                    batch.catalogId(),
                    new CatalogPublishException("All items failed: " + batch.errorCount() + " errors"));
        }
        return ProcessingResult.success(batch.catalogId(), batch);
    }
}
