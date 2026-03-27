package org.beckn.auth.model;

import org.beckn.auth.exception.BecknAuthException;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Standard Beckn acknowledgement response DTO.
 * <p>
 * Mirrors the discovery-service-v2 NACK response pattern so that callers
 * can directly serialize this object to JSON for error responses.
 * </p>
 */
public class AckResponse {

    private final String transactionId;
    private final String timestamp;
    private final String ackStatus;
    private final ErrorDetail error;

    /**
     * Constructs a new AckResponse.
     *
     * @param transactionId the Beckn transaction ID
     * @param timestamp     ISO 8601 formatted timestamp
     * @param ackStatus     "ACK" or "NACK"
     * @param error         error detail (null for ACK responses)
     */
    public AckResponse(String transactionId, String timestamp, String ackStatus, ErrorDetail error) {
        this.transactionId = transactionId;
        this.timestamp = timestamp;
        this.ackStatus = ackStatus;
        this.error = error;
    }

    /**
     * Creates a NACK response with error details.
     *
     * @param transactionId the Beckn transaction ID
     * @param timestamp     ISO 8601 formatted timestamp
     * @param code          the error code constant
     * @param paths         dot-notation path to the failing field
     * @param message       human-readable error message
     * @return a NACK AckResponse
     */
    public static AckResponse nack(String transactionId, String timestamp,
            String code, String paths, String message) {
        ErrorDetail errorDetail = new ErrorDetail(code, paths, message);
        return new AckResponse(transactionId, timestamp, "NACK", errorDetail);
    }

    /**
     * Creates a successful ACK response.
     *
     * @param transactionId the Beckn transaction ID
     * @param timestamp     ISO 8601 formatted timestamp
     * @return an ACK AckResponse
     */
    public static AckResponse ack(String transactionId, String timestamp) {
        return new AckResponse(transactionId, timestamp, "ACK", null);
    }

    /**
     * Builds a NACK response directly from a BecknAuthException.
     *
     * @param exception     the caught exception
     * @param transactionId the Beckn transaction ID
     * @return a NACK AckResponse with error details from the exception
     */
    public static AckResponse fromException(BecknAuthException exception, String transactionId) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return nack(transactionId, timestamp, exception.getCode(), exception.getPaths(), exception.getMessage());
    }

    /** @return the Beckn transaction ID */
    public String getTransactionId() {
        return transactionId;
    }

    /** @return ISO 8601 formatted timestamp */
    public String getTimestamp() {
        return timestamp;
    }

    /** @return "ACK" or "NACK" */
    public String getAckStatus() {
        return ackStatus;
    }

    /** @return error detail, or null for ACK responses */
    public ErrorDetail getError() {
        return error;
    }

    /**
     * Error detail nested within a NACK response.
     */
    public static class ErrorDetail {

        private final String code;
        private final String paths;
        private final String message;

        /**
         * Constructs an ErrorDetail.
         *
         * @param code    error code constant
         * @param paths   dot-notation path to the failing field
         * @param message human-readable error message
         */
        public ErrorDetail(String code, String paths, String message) {
            this.code = code;
            this.paths = paths;
            this.message = message;
        }

        /** @return error code constant */
        public String getCode() {
            return code;
        }

        /** @return dot-notation path */
        public String getPaths() {
            return paths;
        }

        /** @return human-readable message */
        public String getMessage() {
            return message;
        }
    }
}
