package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Location DTO — Beckn Protocol v2.0.
 *
 * <p>Aligned with {@code components/schemas/Location} in beckn.yaml:
 * {@code geo} (GeoJSON, required), {@code address}.
 * {@code additionalProperties: false} in the spec.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Location {

    @NotNull(message = "geo is required")
    @JsonProperty("geo")
    private Geo geo; // Required: GeoJSON geometry

    @JsonProperty("address")
    private Address address; // Optional: Can be string or Address object per schema

    // Default constructor
    public Location() {
    }

    // Constructor with all fields
    public Location(Geo geo, Address address) {
        this.geo = geo;
        this.address = address;
    }

    // Getters and Setters
    public Geo getGeo() {
        return geo;
    }

    public void setGeo(Geo geo) {
        this.geo = geo;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "Location{" +
                "geo=" + geo +
                ", address=" + address +
                '}';
    }

    // Nested Geo class (GeoJSON format)
    public static class Geo {
        @JsonProperty("type")
        private String type; // "Point", "Polygon", etc.

        @JsonProperty("coordinates")
        private java.util.List<Object> coordinates; // [lon, lat] for Point

        public Geo() {
        }

        public Geo(String type, java.util.List<Object> coordinates) {
            this.type = type;
            this.coordinates = coordinates;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public java.util.List<Object> getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(java.util.List<Object> coordinates) {
            this.coordinates = coordinates;
        }

        @Override
        public String toString() {
            return "Geo{" +
                    "type='" + type + '\'' +
                    ", coordinates=" + coordinates +
                    '}';
        }
    }

    // Nested Address class (aligned with schema.org PostalAddress)
    public static class Address {
        @JsonProperty("streetAddress")
        private String streetAddress;

        @JsonProperty("extendedAddress")
        private String extendedAddress;

        @JsonProperty("addressLocality")
        private String addressLocality;

        @JsonProperty("addressRegion")
        private String addressRegion;

        @JsonProperty("postalCode")
        private String postalCode;

        @JsonProperty("addressCountry")
        private String addressCountry;

        public Address() {
        }

        public String getStreetAddress() {
            return streetAddress;
        }

        public void setStreetAddress(String streetAddress) {
            this.streetAddress = streetAddress;
        }

        public String getExtendedAddress() {
            return extendedAddress;
        }

        public void setExtendedAddress(String extendedAddress) {
            this.extendedAddress = extendedAddress;
        }

        public String getAddressLocality() {
            return addressLocality;
        }

        public void setAddressLocality(String addressLocality) {
            this.addressLocality = addressLocality;
        }

        public String getAddressRegion() {
            return addressRegion;
        }

        public void setAddressRegion(String addressRegion) {
            this.addressRegion = addressRegion;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public void setPostalCode(String postalCode) {
            this.postalCode = postalCode;
        }

        public String getAddressCountry() {
            return addressCountry;
        }

        public void setAddressCountry(String addressCountry) {
            this.addressCountry = addressCountry;
        }

        @Override
        public String toString() {
            return "Address{" +
                    "streetAddress='" + streetAddress + '\'' +
                    ", extendedAddress='" + extendedAddress + '\'' +
                    ", addressLocality='" + addressLocality + '\'' +
                    ", addressRegion='" + addressRegion + '\'' +
                    ", postalCode='" + postalCode + '\'' +
                    ", addressCountry='" + addressCountry + '\'' +
                    '}';
        }
    }
}
