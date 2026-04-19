package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonClassDescription("""
    Polymorphic anchor for an AnnotationDef. Two shapes are accepted on
    the wire:
    
    1. Data-coordinate anchor: an object `{ "x": <axis-value>, "y": <axis-value> }`
       positioning the annotation at the given data coordinates. Each
       coordinate is a string-or-number AxisCoord matched against its
       axis scale by the frontend.
    2. Corner anchor: a string literal, one of
       'top-left' | 'top-right' | 'bottom-left' | 'bottom-right',
       positioning the annotation at a fixed corner of the chart frame.
    
    Jackson dispatches based on JSON shape (object vs. string) via a
    custom deserializer; serialization of each subtype is driven by its
    own @JsonValue / record-component emission.""")
@JsonDeserialize(using = AnnotationAnchorDeserializer.class)
public sealed interface AnnotationAnchor permits AnnotationAnchor.DataCoord, AnnotationAnchor.Corner {

    @JsonClassDescription("""
        Corner anchor. Serialized as a plain JSON string:
        'top-left' | 'top-right' | 'bottom-left' | 'bottom-right'.""")
    enum Corner implements AnnotationAnchor {
        @JsonProperty("top-left") TOP_LEFT,
        @JsonProperty("top-right") TOP_RIGHT,
        @JsonProperty("bottom-left") BOTTOM_LEFT,
        @JsonProperty("bottom-right") BOTTOM_RIGHT;

        @JsonCreator
        public static Corner fromWire(String raw) {
            return switch (raw) {
                case "top-left" -> TOP_LEFT;
                case "top-right" -> TOP_RIGHT;
                case "bottom-left" -> BOTTOM_LEFT;
                case "bottom-right" -> BOTTOM_RIGHT;
                default -> throw new IllegalArgumentException("Unknown corner anchor: " + raw);
            };
        }

        @JsonValue
        public String wire() {
            return switch (this) {
                case TOP_LEFT -> "top-left";
                case TOP_RIGHT -> "top-right";
                case BOTTOM_LEFT -> "bottom-left";
                case BOTTOM_RIGHT -> "bottom-right";
            };
        }
    }

    @JsonClassDescription("""
        Data-coordinate anchor placing the annotation at the given (x, y).
        Both coordinates are polymorphic AxisCoord values (string or number)
        so they can address any axis kind: numeric, time (ISO string or epoch),
        or categorical (label string).""")
    record DataCoord(

        @JsonPropertyDescription("X coordinate as an AxisCoord (string or number).")
        @JsonProperty(required = true)
        AxisCoord x,

        @JsonPropertyDescription("Y coordinate as an AxisCoord (string or number).")
        @JsonProperty(required = true)
        AxisCoord y
    ) implements AnnotationAnchor {}
}
