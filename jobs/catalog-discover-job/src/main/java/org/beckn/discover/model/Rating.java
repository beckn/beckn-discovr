package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * Rating DTO
 * 
 * Represents a rating for items or providers.
 */
public class Rating {

    @NotBlank(message = "@type is required")
    @JsonProperty("@type")
    private String type;

    @DecimalMin(value = "0.0", message = "Rating value must be at least 0.0")
    @DecimalMax(value = "5.0", message = "Rating value must be at most 5.0")
    @JsonProperty("ratingValue")
    private Double ratingValue;

    @Min(value = 0, message = "Rating count must be at least 0")
    @JsonProperty("ratingCount")
    private Integer ratingCount;

    // Default constructor
    public Rating() {}

    // Constructor with required fields
    public Rating(String type) {
        this.type = type;
    }

    // Constructor with all fields
    public Rating(String type, Double ratingValue, Integer ratingCount) {
        this.type = type;
        this.ratingValue = ratingValue;
        this.ratingCount = ratingCount;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getRatingValue() {
        return ratingValue;
    }

    public void setRatingValue(Double ratingValue) {
        this.ratingValue = ratingValue;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(Integer ratingCount) {
        this.ratingCount = ratingCount;
    }

    @Override
    public String toString() {
        return "Rating{" +
                "type='" + type + '\'' +
                ", ratingValue=" + ratingValue +
                ", ratingCount=" + ratingCount +
                '}';
    }
}
