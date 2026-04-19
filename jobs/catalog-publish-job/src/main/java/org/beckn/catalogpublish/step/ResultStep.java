package org.beckn.catalogpublish.step;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.exception.CatalogPublishException;
import org.springframework.stereotype.Component;

@Component
public class ResultStep {

    public ProcessingResult toProcessingResult(CatalogBatch batch) {
        if (batch.savedResources().isEmpty() && batch.hasErrors()) {
            return ProcessingResult.rejected(
                    batch.catalogId(),
                    new CatalogPublishException("All resources failed: " + batch.errorCount() + " errors"));
        }
        return ProcessingResult.success(batch.catalogId(), batch);
    }
}
