package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * TimePeriod DTO
 * 
 * Represents a time window with date-time precision for availability/validity.
 */
public class TimePeriod {

    @NotBlank(message = "@type is required")
    @JsonProperty("@type")
    private String type;

    @JsonProperty("schema:startDate")
    private OffsetDateTime startDate;

    @JsonProperty("schema:endDate")
    private OffsetDateTime endDate;

    @JsonProperty("schema:startTime")
    private String startTime;

    @JsonProperty("schema:endTime")
    private String endTime;

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    // Default constructor
    public TimePeriod() {
    }

    // Constructor with required fields
    public TimePeriod(String type) {
        this.type = type;
    }

    // Constructor with all fields
    public TimePeriod(String type, OffsetDateTime startDate, OffsetDateTime endDate) {
        this.type = type;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OffsetDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(OffsetDateTime startDate) {
        this.startDate = startDate;
    }

    public OffsetDateTime getEndDate() {
        return endDate;
    }

    public void setEndDate(OffsetDateTime endDate) {
        this.endDate = endDate;
    }

    @Override
    public String toString() {
        return "TimePeriod{" +
                "type='" + type + '\'' +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", startTime='" + startTime + '\'' +
                ", endTime='" + endTime + '\'' +
                '}';
    }
}
