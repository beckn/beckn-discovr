package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * TimePeriod DTO — Beckn Protocol v2.0.
 *
 * <p>Aligned with {@code components/schemas/TimePeriod} in beckn.yaml:
 * {@code startDate}, {@code endDate}, {@code startTime}, {@code endTime}.
 * {@code additionalProperties: false} in the spec.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimePeriod {

    @JsonProperty("startDate")
    private OffsetDateTime startDate;

    @JsonProperty("endDate")
    private OffsetDateTime endDate;

    @JsonProperty("startTime")
    private String startTime;

    @JsonProperty("endTime")
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

    // Constructor with all fields
    public TimePeriod(OffsetDateTime startDate, OffsetDateTime endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // Getters and Setters
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
                "startDate=" + startDate +
                ", endDate=" + endDate +
                ", startTime='" + startTime + '\'' +
                ", endTime='" + endTime + '\'' +
                '}';
    }
}
