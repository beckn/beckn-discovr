package org.beckn.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Jackson-mapped views of the three DeDi files. Only the fields the POC reads are declared;
 * everything else (keys, proof, schema, …) is ignored — see the design doc §4.
 *
 * <p>The DeDi wrapper uses snake_case ({@code record_name}, {@code next_update}); the inner
 * catalog details use camelCase ({@code catalogId}, {@code lastModified}). Both are mapped
 * explicitly so nothing depends on a global naming strategy.
 */
public final class FeedModels {

    private FeedModels() {}

    /** manifest — {@code /.well-known/dedi.json} (type: dedi-manifest). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(String type, String domain, String name, List<FileRef> files) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FileRef(String registry, String url, String digest, String state) {
            /** True only when the index registry is live (DeDi state vocabulary). */
            public boolean isLive() {
                return "live".equalsIgnoreCase(state);
            }
        }
    }

    /** index — {@code /dedi/…dedi.json} (type: dedi-file). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Index(
            String type,
            Publisher publisher,
            String namespace,
            @JsonProperty("next_update") String nextUpdate,
            List<Record> records) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Publisher(String domain) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Record(@JsonProperty("record_name") String recordName, Details details) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Details(
                String catalogId,
                long version,
                String catalogType,
                String status,          // ACTIVE | RETIRED
                Object visibility,      // "public" (String) OR { "networks": [...] } (Map)
                String updatedAt,
                List<Part> parts) {

            /** True only when visibility is exactly the string "public". */
            public boolean isPublic() {
                return "public".equals(visibility);
            }

            public boolean isRetired() {
                return "RETIRED".equalsIgnoreCase(status);
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Part(String url, String digest, String lastModified) {}
    }
}
