package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Reference line drawn across a chart at a constant value on one axis.
    axis='x' -> vertical line at x = value; axis='y' -> horizontal line
    at y = value.""")
public record ReferenceLineDef(

    @JsonPropertyDescription("Axis value at which to draw the reference line, in chart units.")
    @JsonProperty(required = true)
    double value,

    @JsonPropertyDescription("Optional label rendered next to the line (e.g. 'target', 'baseline').")
    @Nullable String label,

    @JsonPropertyDescription("Which axis to anchor to. 'x' = vertical line; 'y' = horizontal line.")
    @JsonProperty(required = true)
    AxisAnchor axis,

    @JsonPropertyDescription("Stroke style: 'solid' (default), 'dashed', or 'dotted'.")
    @Nullable ReferenceLineStyle style
) {}
