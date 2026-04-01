package org.beckn.seeker.service;

/**
 * Thrown when a callback HTTP delivery fails after all retry attempts are exhausted.
 */
public class CallbackDeliveryException extends RuntimeException {

    public CallbackDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public CallbackDeliveryException(String message) {
        super(message);
    }
}
