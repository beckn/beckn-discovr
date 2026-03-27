package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * Rating DTO
 * 
 * Represents a rating for items or providers.
 */
public class Rating {

    @JsonProperty("reviewText")
    private String reviewText;

    @DecimalMin(value = "0.0", message = "Rating value must be at least 0.0")
    @DecimalMax(value = "5.0", message = "Rating value must be at most 5.0")
    @JsonProperty("ratingValue")
    private Double ratingValue;

    @Min(value = 0, message = "Rating count must be at least 0")
    @JsonProperty("ratingCount")
    private Integer ratingCount;

    // Default constructor
    public Rating() {}

    // Constructor with all fields
    public Rating(Double ratingValue, Integer ratingCount) {
        this.ratingValue = ratingValue;
        this.ratingCount = ratingCount;
    }

    // Getters and Setters
    public String getReviewText() { return reviewText; }
    public void setReviewText(String reviewText) { this.reviewText = reviewText; }

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
                "ratingValue=" + ratingValue +
                ", ratingCount=" + ratingCount +
                '}';
    }
}
