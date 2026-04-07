package org.beckn.auth.model;

import org.beckn.auth.exception.BecknAuthException;

/**
 * Standard Beckn v2.1 acknowledgement response DTO.
 * <p>
 * ACK format:  {@code {"status":"ACK"}}
 * NACK format: {@code {"status":"NACK","error":{"errorCode":"...","errorMessage":"..."}}}
 * </p>
 */
public record AckResponse(String status, ErrorDetail error) {

    /**
     * Error detail nested within a NACK response.
     */
    public record ErrorDetail(String errorCode, String errorMessage) {}

    /**
     * Creates a NACK response with error details.
     *
     * @param errorCode    the Beckn error code constant
     * @param errorMessage human-readable error message
     * @return a NACK AckResponse
     */
    public static AckResponse nack(String errorCode, String errorMessage) {
        return new AckResponse("NACK", new ErrorDetail(errorCode, errorMessage));
    }

    /**
     * Creates a successful ACK response.
     *
     * @return an ACK AckResponse
     */
    public static AckResponse ack() {
        return new AckResponse("ACK", null);
    }

    /**
     * Builds a NACK response directly from a BecknAuthException.
     *
     * @param exception the caught exception
     * @return a NACK AckResponse with error details from the exception
     */
    public static AckResponse fromException(BecknAuthException exception) {
        return nack(exception.getCode(), exception.getMessage());
    }
}
