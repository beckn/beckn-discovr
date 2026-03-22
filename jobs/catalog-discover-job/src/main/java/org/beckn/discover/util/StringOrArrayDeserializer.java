package org.beckn.discover.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializes a JSON field that may be either a bare string or an array of strings
 * into {@code List<String>}. Handles both Beckn v2.0 (single string) and v2.1 (array) formats.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code "retail-grocery"} → {@code ["retail-grocery"]}</li>
 *   <li>{@code ["retail-grocery", "net-002"]} → {@code ["retail-grocery", "net-002"]}</li>
 * </ul>
 */
public class StringOrArrayDeserializer extends StdDeserializer<List<String>> {

    public StringOrArrayDeserializer() {
        super(List.class);
    }

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<String> list = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                list.add(p.getText());
            }
            return list;
        }
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            String value = p.getText();
            if (value == null || value.isBlank()) return List.of();
            return List.of(value);
        }
        return List.of();
    }
}
