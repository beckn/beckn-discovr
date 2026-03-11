package org.beckn.catalogpublish.messaging;

public interface FailedMessagePublisher {

    void publishFailed(String originalMessage, String reason);
}
