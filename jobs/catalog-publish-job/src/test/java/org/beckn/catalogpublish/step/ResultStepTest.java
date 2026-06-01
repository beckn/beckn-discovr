package org.beckn.catalogpublish.step;

import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.beckn.catalogpublish.dto.ProcessingError;
import org.beckn.catalogpublish.dto.ProcessingErrorCode;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.dto.ProcessingStatus;
import org.beckn.catalogpublish.model.Item;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultStepTest {

    private final ResultStep resultStep = new ResultStep();

    @Test
    void toProcessingResult_successWhenItemsSaved() {
        CatalogContext ctx = new CatalogContext(List.of(), null);
        Item item = Item.from("i1", "{}", new String[0], "c1", null, null, new String[0]);
        CatalogBatch batch = new CatalogBatch("c1", ctx, null, CatalogOperation.PUBLISH, List.of(item), List.of(),
                Map.of(), false);
        ProcessingResult result = resultStep.toProcessingResult(batch);
        assertThat(result).isInstanceOf(ProcessingResult.Success.class);
        assertThat(result.status()).isEqualTo(ProcessingStatus.ACCEPTED);
    }

    @Test
    void toProcessingResult_rejectedWhenNoItemsSavedAndHasErrors() {
        CatalogContext ctx = new CatalogContext(List.of(), null);
        CatalogBatch batch = new CatalogBatch("c1", ctx, null, CatalogOperation.PUBLISH, List.of(),
                List.of(new ProcessingError("i1", ProcessingErrorCode.NET_INTERNAL_ERROR, "err")), Map.of(), false);
        ProcessingResult result = resultStep.toProcessingResult(batch);
        assertThat(result).isInstanceOf(ProcessingResult.Rejected.class);
    }
}
