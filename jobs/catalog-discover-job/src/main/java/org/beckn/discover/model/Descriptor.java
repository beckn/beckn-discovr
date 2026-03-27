package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

import java.util.List;
import java.util.Map;

/**
 * Descriptor DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
public class Descriptor {

    @JsonProperty(BecknFields.NAME)
    private String name;

    @JsonProperty(BecknFields.SHORT_DESC)
    private String shortDesc;

    @JsonProperty(BecknFields.LONG_DESC)
    private String longDesc;

    @JsonProperty(BecknFields.IMAGES)
    private List<String> image;

    @JsonProperty("thumbnailImage")
    private String thumbnailImage;

    @JsonProperty("docs")
    private List<Map<String, Object>> docs;

    @JsonProperty("mediaFile")
    private List<Map<String, Object>> mediaFile;

    // Default constructor
    public Descriptor() {}

    public Descriptor(String name) {
        this.name = name;
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortDesc() { return shortDesc; }
    public void setShortDesc(String shortDesc) { this.shortDesc = shortDesc; }

    public String getLongDesc() { return longDesc; }
    public void setLongDesc(String longDesc) { this.longDesc = longDesc; }

    public List<String> getImage() { return image; }
    public void setImage(List<String> image) { this.image = image; }

    public String getThumbnailImage() { return thumbnailImage; }
    public void setThumbnailImage(String thumbnailImage) { this.thumbnailImage = thumbnailImage; }

    public List<Map<String, Object>> getDocs() { return docs; }
    public void setDocs(List<Map<String, Object>> docs) { this.docs = docs; }

    public List<Map<String, Object>> getMediaFile() { return mediaFile; }
    public void setMediaFile(List<Map<String, Object>> mediaFile) { this.mediaFile = mediaFile; }

    @Override
    public String toString() {
        return "Descriptor{name='" + name + "'}";
    }
}
