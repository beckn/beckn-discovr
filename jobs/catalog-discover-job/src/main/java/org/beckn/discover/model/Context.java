package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Beckn Context DTO containing message metadata.
 *
 * Represents the context information from Beckn Discovery requests including
 * message identification, transaction details, and timestamps.
 * Based on Beckn Protocol v2.1 Context schema (camelCase field names only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Context {

    @NotBlank(message = "Message ID is required")
    @JsonProperty(BecknFields.MESSAGE_ID)
    private String messageId;

    @NotBlank(message = "BAP ID is required")
    @JsonProperty(BecknFields.BAP_ID)
    private String bapId;

    @NotBlank(message = "Transaction ID is required")
    @JsonProperty(BecknFields.TRANSACTION_ID)
    private String transactionId;

    @NotNull(message = "Timestamp is required")
    @JsonProperty(BecknFields.TIMESTAMP)
    private OffsetDateTime timestamp;

    @JsonProperty(BecknFields.DOMAIN)
    private String domain;

    @JsonProperty(BecknFields.ACTION)
    private String action;

    @JsonProperty(BecknFields.VERSION)
    private String version;

    @JsonProperty(BecknFields.BPP_ID)
    private String bppId;

    @JsonProperty(BecknFields.BPP_URI)
    private String bppUri;

    @JsonProperty(BecknFields.COUNTRY)
    private String country;

    @JsonProperty(BecknFields.CITY)
    private String city;

    @JsonProperty(BecknFields.TTL)
    private String ttl;

    @JsonProperty(BecknFields.BAP_URI)
    private String bapUri;

    @JsonProperty(BecknFields.NETWORK_ID)
    private String networkId;

    @JsonProperty(BecknFields.SCHEMA_CONTEXT)
    private List<String> schemaContext;

    // Default constructor
    public Context() {}

    // Constructor with required fields
    public Context(String messageId, String bapId, String transactionId, OffsetDateTime timestamp) {
        this.messageId = messageId;
        this.bapId = bapId;
        this.transactionId = transactionId;
        this.timestamp = timestamp;
    }

    /** Copy constructor -- copies all fields from another Context. */
    public Context(Context other) {
        if (other == null) return;
        this.messageId = other.messageId;
        this.bapId = other.bapId;
        this.transactionId = other.transactionId;
        this.timestamp = other.timestamp;
        this.domain = other.domain;
        this.action = other.action;
        this.version = other.version;
        this.bppId = other.bppId;
        this.bppUri = other.bppUri;
        this.country = other.country;
        this.city = other.city;
        this.ttl = other.ttl;
        this.bapUri = other.bapUri;
        this.networkId = other.networkId;
        this.schemaContext = other.schemaContext != null ? new java.util.ArrayList<>(other.schemaContext) : null;
    }

    // Getters and Setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getBapId() { return bapId; }
    public void setBapId(String bapId) { this.bapId = bapId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public OffsetDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getBppId() { return bppId; }
    public void setBppId(String bppId) { this.bppId = bppId; }

    public String getBppUri() { return bppUri; }
    public void setBppUri(String bppUri) { this.bppUri = bppUri; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getTtl() { return ttl; }
    public void setTtl(String ttl) { this.ttl = ttl; }

    public String getBapUri() { return bapUri; }
    public void setBapUri(String bapUri) { this.bapUri = bapUri; }

    public String getNetworkId() { return networkId; }
    public void setNetworkId(String networkId) { this.networkId = networkId; }

    public List<String> getSchemaContext() { return schemaContext; }
    public void setSchemaContext(List<String> schemaContext) { this.schemaContext = schemaContext; }

    @Override
    public String toString() {
        return "Context{" +
                "messageId='" + messageId + '\'' +
                ", bapId='" + bapId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", timestamp=" + timestamp +
                ", domain='" + domain + '\'' +
                ", action='" + action + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}
