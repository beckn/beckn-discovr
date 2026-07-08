package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

/**
 * Legacy flat ACK/NACK Response DTO — the pre-2.0 shape used before commit 03066d7.
 *
 * <p>Emitted only when {@code discovery.legacy-ack-nack-support=true}. For back-compat with
 * BAPs that were built against the original synchronous response body:</p>
 *
 * <ul>
 *   <li>ACK:  {@code {"status":"ACK"}}</li>
 *   <li>NACK: {@code {"status":"NACK","error":{"errorCode":"...","errorMessage":"..."}}}</li>
 * </ul>
 *
 * <p>Root-level {@code status}, NO {@code message} wrapper, NO {@code messageId}. The NACK
 * error uses {@code errorCode}/{@code errorMessage} (not {@code code}/{@code message}). The error
 * code VALUE and message VALUE are identical to the v2.0 {@link AckResponse} — only the envelope
 * shape and the field names differ.</p>
 *
 * @see AckResponse the default Beckn v2.0 nested shape
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LegacyAckResponse implements AckResponseBody {

    @JsonProperty(BecknFields.STATUS)
    private final String status; // "ACK" or "NACK"

    @JsonProperty(BecknFields.ERROR)
    private final ErrorDetail error;

    private LegacyAckResponse(String status, ErrorDetail error) {
        this.status = status;
        this.error = error;
    }

    /** Factory: accepted request. */
    public static LegacyAckResponse ack() {
        return new LegacyAckResponse("ACK", null);
    }

    /** Factory: rejected request. */
    public static LegacyAckResponse nack(String errorCode, String errorMessage) {
        return new LegacyAckResponse("NACK", new ErrorDetail(errorCode, errorMessage));
    }

    public String getStatus() { return status; }
    public ErrorDetail getError() { return error; }

    @Override
    public String toString() {
        return "LegacyAckResponse{status='" + status + "', error=" + error + '}';
    }

    /**
     * Legacy error detail — fields are {@code errorCode}/{@code errorMessage}
     * (pre-2.0 names), carrying the same values as the v2.0 {@code code}/{@code message}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {

        @JsonProperty(BecknFields.ERROR_CODE)
        private final String errorCode;

        @JsonProperty(BecknFields.ERROR_MESSAGE)
        private final String errorMessage;

        public ErrorDetail(String errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }

        @Override
        public String toString() {
            return "ErrorDetail{errorCode='" + errorCode + "', errorMessage='" + errorMessage + "'}";
        }
    }
}
