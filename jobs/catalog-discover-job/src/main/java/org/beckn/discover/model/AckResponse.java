package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

/**
 * ACK/NACK Response DTO — Beckn Protocol v2.0 format.
 *
 * ACK:  {@code {"status": "ACK"}}
 * NACK: {@code {"status": "NACK", "error": {"errorCode": "...", "errorMessage": "..."}}}
 *
 * @version 2.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AckResponse {

    @JsonProperty(BecknFields.STATUS)
    private String status; // "ACK" or "NACK"

    @JsonProperty(BecknFields.ERROR)
    private ErrorDetail error;

    public AckResponse() {}

    public AckResponse(String status) {
        this.status = status;
    }

    public AckResponse(String status, ErrorDetail error) {
        this.status = status;
        this.error = error;
    }

    // Static factory methods
    public static AckResponse ack() {
        return new AckResponse("ACK");
    }

    public static AckResponse nack(String errorCode, String errorMessage) {
        return new AckResponse("NACK", new ErrorDetail(errorCode, errorMessage));
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public ErrorDetail getError() { return error; }
    public void setError(ErrorDetail error) { this.error = error; }

    @Override
    public String toString() {
        return "AckResponse{status='" + status + "', error=" + error + '}';
    }

    /**
     * Error Detail DTO — Beckn Protocol v2.0 format.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        @JsonProperty(BecknFields.ERROR_CODE)
        private String errorCode;

        @JsonProperty(BecknFields.ERROR_MESSAGE)
        private String errorMessage;

        public ErrorDetail() {}

        public ErrorDetail(String errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        @Override
        public String toString() {
            return "ErrorDetail{errorCode='" + errorCode + "', errorMessage='" + errorMessage + "'}";
        }
    }
}
