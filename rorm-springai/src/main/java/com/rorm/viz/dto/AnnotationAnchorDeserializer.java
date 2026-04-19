package com.rorm.viz.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/**
 * Dispatches the object|string union shape of {@link AnnotationAnchor}:
 * - JSON object  -> {@link AnnotationAnchor.DataCoord}
 * - JSON string  -> {@link AnnotationAnchor.Corner}
 * <p>
 * Parses each shape manually rather than delegating to {@code ctx.readValue(...)}
 * because {@link com.fasterxml.jackson.databind.annotation.JsonDeserialize @JsonDeserialize}
 * on the sealed interface is inherited by the subtypes — a delegating readValue
 * would re-enter this deserializer and stack-overflow.
 */
public class AnnotationAnchorDeserializer extends JsonDeserializer<AnnotationAnchor> {

    @Override
    public AnnotationAnchor deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        var token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return AnnotationAnchor.Corner.fromWire(p.getValueAsString());
        }
        if (token == JsonToken.START_OBJECT) {
            return readDataCoord(p);
        }
        throw JsonMappingException.from(p,
            "Expected JSON object (data coord) or string (corner), got " + token);
    }

    private AnnotationAnchor.DataCoord readDataCoord(JsonParser p) throws IOException {
        AxisCoord x = null;
        AxisCoord y = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            if (p.currentToken() != JsonToken.FIELD_NAME) {
                continue;
            }
            var field = p.currentName();
            p.nextToken();
            switch (field) {
                case "x" -> x = readAxisCoord(p);
                case "y" -> y = readAxisCoord(p);
                default -> p.skipChildren();
            }
        }
        if (x == null || y == null) {
            throw JsonMappingException.from(p, "DataCoord requires both 'x' and 'y' fields");
        }
        return new AnnotationAnchor.DataCoord(x, y);
    }

    private AxisCoord readAxisCoord(JsonParser p) throws IOException {
        var token = p.currentToken();
        return switch (token) {
            case VALUE_STRING -> new AxisCoord(p.getValueAsString());
            case VALUE_NUMBER_INT -> new AxisCoord(p.getNumberValue());
            case VALUE_NUMBER_FLOAT -> new AxisCoord(p.getDoubleValue());
            case VALUE_TRUE, VALUE_FALSE -> new AxisCoord(p.getBooleanValue());
            case VALUE_NULL -> throw JsonMappingException.from(p,
                "AxisCoord values cannot be null");
            default -> throw JsonMappingException.from(p,
                "AxisCoord must be a string or number, got " + token);
        };
    }
}
