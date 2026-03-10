package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Descriptor DTO
 * 
 * Represents the descriptor information for items, catalogs, and providers.
 */
public class Descriptor {

    @NotBlank(message = "@type is required")
    @JsonProperty("@type")
    private String type;

    @JsonProperty("schema:name")
    private String name;

    @JsonProperty("beckn:shortDesc")
    private String shortDesc;

    @JsonProperty("beckn:longDesc")
    private String longDesc;

    @JsonProperty("schema:image")
    private List<String> image;

    // Default constructor
    public Descriptor() {}

    // Constructor with required fields
    public Descriptor(String type) {
        this.type = type;
    }

    // Constructor with type and name
    public Descriptor(String type, String name) {
        this.type = type;
        this.name = name;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortDesc() {
        return shortDesc;
    }

    public void setShortDesc(String shortDesc) {
        this.shortDesc = shortDesc;
    }

    public String getLongDesc() {
        return longDesc;
    }

    public void setLongDesc(String longDesc) {
        this.longDesc = longDesc;
    }

    public List<String> getImage() {
        return image;
    }

    public void setImage(List<String> image) {
        this.image = image;
    }

    @Override
    public String toString() {
        return "Descriptor{" +
                "type='" + type + '\'' +
                ", name='" + name + '\'' +
                ", shortDesc='" + shortDesc + '\'' +
                ", longDesc='" + longDesc + '\'' +
                ", image=" + image +
                '}';
    }
}
