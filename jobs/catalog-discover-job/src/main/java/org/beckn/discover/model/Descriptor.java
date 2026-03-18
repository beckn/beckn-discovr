package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Descriptor DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
public class Descriptor {

    @JsonProperty("@type")
    private String type;

    @JsonProperty(BecknFields.NAME)
    private String name;

    @JsonProperty(BecknFields.SHORT_DESC)
    private String shortDesc;

    @JsonProperty(BecknFields.LONG_DESC)
    private String longDesc;

    @JsonProperty(BecknFields.IMAGES)
    private List<String> image;

    // Default constructor
    public Descriptor() {}

    public Descriptor(String type) {
        this.type = type;
    }

    public Descriptor(String type, String name) {
        this.type = type;
        this.name = name;
    }

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortDesc() { return shortDesc; }
    public void setShortDesc(String shortDesc) { this.shortDesc = shortDesc; }

    public String getLongDesc() { return longDesc; }
    public void setLongDesc(String longDesc) { this.longDesc = longDesc; }

    public List<String> getImage() { return image; }
    public void setImage(List<String> image) { this.image = image; }

    @Override
    public String toString() {
        return "Descriptor{type='" + type + "', name='" + name + "'}";
    }
}
