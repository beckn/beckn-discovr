package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * CategoryCode DTO
 * 
 * Represents a category code for items.
 */
public class CategoryCode {

    @NotBlank(message = "@type is required")
    @JsonProperty("@type")
    private String type;

    @NotBlank(message = "codeValue is required")
    @JsonProperty("codeValue")
    private String codeValue;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    // Default constructor
    public CategoryCode() {}

    // Constructor with required fields
    public CategoryCode(String type, String codeValue) {
        this.type = type;
        this.codeValue = codeValue;
    }

    // Constructor with all fields
    public CategoryCode(String type, String codeValue, String name, String description) {
        this.type = type;
        this.codeValue = codeValue;
        this.name = name;
        this.description = description;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCodeValue() {
        return codeValue;
    }

    public void setCodeValue(String codeValue) {
        this.codeValue = codeValue;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "CategoryCode{" +
                "type='" + type + '\'' +
                ", codeValue='" + codeValue + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
