package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ACK/NACK Response DTO
 * 
 * Represents acknowledgment response for Beckn discovery requests.
 * Based on schema.json AckResponse structure.
 * 
 * Returns ACK for successful receipt/validation or NACK for errors.
 * 
 * @version 2.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AckResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("ack_status")
    private String ackStatus; // "ACK" or "NACK"

    @JsonProperty("error")
    private ErrorDetail error;

    // Default constructor
    public AckResponse() {}

    // Constructor for ACK
    public AckResponse(String transactionId, String timestamp, String ackStatus) {
        this.transactionId = transactionId;
        this.timestamp = timestamp;
        this.ackStatus = ackStatus;
    }

    // Constructor for NACK with error
    public AckResponse(String transactionId, String timestamp, String ackStatus, ErrorDetail error) {
        this.transactionId = transactionId;
        this.timestamp = timestamp;
        this.ackStatus = ackStatus;
        this.error = error;
    }

    // Static factory methods
    public static AckResponse ack(String transactionId, String timestamp) {
        return new AckResponse(transactionId, timestamp, "ACK");
    }

    public static AckResponse nack(String transactionId, String timestamp, String code, 
                                   String paths, String message) {
        ErrorDetail error = new ErrorDetail(code, paths, message);
        return new AckResponse(transactionId, timestamp, "NACK", error);
    }

    // Getters and setters
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getAckStatus() {
        return ackStatus;
    }

    public void setAckStatus(String ackStatus) {
        this.ackStatus = ackStatus;
    }

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "AckResponse{" +
                "transactionId='" + transactionId + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", ackStatus='" + ackStatus + '\'' +
                ", error=" + error +
                '}';
    }

    /**
     * Error Detail DTO
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        @JsonProperty("code")
        private String code;

        @JsonProperty("paths")
        private String paths;

        @JsonProperty("message")
        private String message;

        public ErrorDetail() {}

        public ErrorDetail(String code, String paths, String message) {
            this.code = code;
            this.paths = paths;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getPaths() {
            return paths;
        }

        public void setPaths(String paths) {
            this.paths = paths;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "ErrorDetail{" +
                    "code='" + code + '\'' +
                    ", paths='" + paths + '\'' +
                    ", message='" + message + '\'' +
                    '}';
        }
    }
}
