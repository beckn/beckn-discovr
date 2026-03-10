package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * NLWeb Request DTO
 * 
 * Represents the request to NLWeb natural language querying engine.
 */
public class NLWebRequest {

    @NotBlank(message = "Query is required")
    @JsonProperty("query")
    private String query;

    // Default constructor
    public NLWebRequest() {}

    // Constructor with required fields
    public NLWebRequest(String query) {
        this.query = query;
    }

    // Getters and Setters
    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    @Override
    public String toString() {
        return "NLWebRequest{" +
                "query='" + query + '\'' +
                '}';
    }
}
