package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

/**
 * ACK/NACK Response DTO — Beckn Protocol v2.0 schema.
 *
 * ACK:  {@code {"message":{"status":"ACK","messageId":"<uuid>"}}}
 * NACK: {@code {"message":{"status":"NACK","messageId":"<uuid>","error":{"code":"...","message":"..."}}}}
 *
 * <p>{@code message.messageId} echoes the request's {@code context.messageId}.
 * When the request is unparseable (malformed JSON) and no messageId is recoverable,
 * the field is simply omitted ({@code @JsonInclude(NON_NULL)}) — never fabricated.</p>
 *
 * @version 2.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AckResponse {

    @JsonProperty(BecknFields.MESSAGE)
    private Message message;

    public AckResponse() {}

    public AckResponse(Message message) {
        this.message = message;
    }

    /** Factory: accepted request. */
    public static AckResponse ack(String messageId) {
        return new AckResponse(new Message("ACK", messageId, null));
    }

    /** Factory: rejected request. */
    public static AckResponse nack(String messageId, String code, String message) {
        return new AckResponse(new Message("NACK", messageId, new ErrorDetail(code, message)));
    }

    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }

    @Override
    public String toString() {
        return "AckResponse{message=" + message + '}';
    }

    /**
     * The {@code message} wrapper — carries {@code status}, {@code messageId}, and optional {@code error}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {

        @JsonProperty(BecknFields.STATUS)
        private String status;

        @JsonProperty(BecknFields.MESSAGE_ID)
        private String messageId;

        @JsonProperty(BecknFields.ERROR)
        private ErrorDetail error;

        public Message() {}

        public Message(String status, String messageId, ErrorDetail error) {
            this.status = status;
            this.messageId = messageId;
            this.error = error;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public ErrorDetail getError() { return error; }
        public void setError(ErrorDetail error) { this.error = error; }

        @Override
        public String toString() {
            return "Message{status='" + status + "', messageId='" + messageId + "', error=" + error + '}';
        }
    }

    /**
     * Error detail — Beckn Protocol v2.0 {@code Error} schema.
     * Fields are {@code code} and {@code message} per the spec.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {

        @JsonProperty(BecknFields.CODE)
        private String code;

        @JsonProperty(BecknFields.MESSAGE)
        private String message;

        public ErrorDetail() {}

        public ErrorDetail(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        @Override
        public String toString() {
            return "ErrorDetail{code='" + code + "', message='" + message + "'}";
        }
    }
}
