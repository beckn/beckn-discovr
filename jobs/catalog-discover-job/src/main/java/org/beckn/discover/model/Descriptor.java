package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.beckn.discover.common.BecknFields;

import java.util.List;
import java.util.Map;

/**
 * Descriptor DTO — Beckn Protocol v2.0.
 *
 * <p>Aligned with {@code components/schemas/Descriptor} in beckn.yaml:
 * {@code code}, {@code name}, {@code shortDesc}, {@code longDesc},
 * {@code thumbnailImage}, {@code docs}, {@code mediaFile}.
 * {@code additionalProperties: false} in the spec.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Descriptor {

    @JsonProperty("code")
    private String code;

    @JsonProperty(BecknFields.NAME)
    private String name;

    @JsonProperty(BecknFields.SHORT_DESC)
    private String shortDesc;

    @JsonProperty(BecknFields.LONG_DESC)
    private String longDesc;

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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortDesc() { return shortDesc; }
    public void setShortDesc(String shortDesc) { this.shortDesc = shortDesc; }

    public String getLongDesc() { return longDesc; }
    public void setLongDesc(String longDesc) { this.longDesc = longDesc; }

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
