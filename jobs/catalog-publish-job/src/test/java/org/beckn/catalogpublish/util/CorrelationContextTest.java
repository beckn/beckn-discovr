package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.logging.MdcField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationContext — subscriptionId MDC population (beckn-discovr#398)")
class CorrelationContextTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CorrelationContext correlationContext;

    @BeforeEach
    void setUp() {
        correlationContext = new CorrelationContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private ObjectNode context() {
        return mapper.createObjectNode()
                .put("transactionId", "txn-1")
                .put("catalogId", "CAT-1");
    }

    @Nested
    @DisplayName("populate (async publish pipeline)")
    class Populate {

        @Test
        void setsSubscriptionIdWhenPresentInContext() {
            ObjectNode ctxNode = context().put("subscriptionId", "sub-abc-123");
            correlationContext.populate(new CatalogContext(List.of("ondc-retail"), ctxNode), "m1");
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isEqualTo("sub-abc-123");
        }

        @Test
        void doesNotSetSubscriptionIdWhenAbsent() {
            correlationContext.populate(new CatalogContext(List.of("ondc-retail"), context()), "m1");
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isNull();
        }

        @Test
        void doesNotSetSubscriptionIdWhenBlank() {
            ObjectNode ctxNode = context().put("subscriptionId", "  ");
            correlationContext.populate(new CatalogContext(List.of("ondc-retail"), ctxNode), "m1");
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isNull();
        }

        @Test
        void clearsStaleSubscriptionIdWhenAbsentOnSubsequentContext() {
            MDC.put(MdcField.SUBSCRIPTION_ID, "stale-from-previous-message");
            correlationContext.populate(new CatalogContext(List.of("ondc-retail"), context()), "m1");
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("populateEntryIds (synchronous catalog/push entry)")
    class PopulateEntryIds {

        @Test
        void setsSubscriptionIdWhenPresentInPushContext() {
            ObjectNode ctxNode = context().put("subscriptionId", "sub-xyz-789");
            correlationContext.populateEntryIds(ctxNode);
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isEqualTo("sub-xyz-789");
        }

        @Test
        void doesNotSetSubscriptionIdWhenAbsent() {
            correlationContext.populateEntryIds(context());
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("populateFallback")
    class PopulateFallback {

        @Test
        void clearsSubscriptionId() {
            MDC.put(MdcField.SUBSCRIPTION_ID, "sub-to-clear");
            correlationContext.populateFallback();
            assertThat(MDC.get(MdcField.SUBSCRIPTION_ID)).isNull();
        }
    }
}
