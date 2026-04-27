package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Payload for scatter plots: a flat list of (x, y) points with optional size / color / label / category.")
public record ScatterData(

    @JsonPropertyDescription("Scatter points. Order is not significant.")
    @JsonProperty(required = true)
    List<ScatterPoint> points
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One scatter point.")
    public record ScatterPoint(

        @JsonPropertyDescription("X coordinate in chart units.")
        @JsonProperty(required = true)
        double x,

        @JsonPropertyDescription("Y coordinate in chart units.")
        @JsonProperty(required = true)
        double y,

        @JsonPropertyDescription("Optional bubble-size magnitude. Drives point radius.")
        @Nullable Double size,

        @JsonPropertyDescription("Optional per-point color role.")
        @Nullable String color,

        @JsonPropertyDescription("Optional point label (tooltip / near-label).")
        @Nullable String label,

        @JsonPropertyDescription("Optional categorical grouping key. Points with the same key share visual treatment.")
        @Nullable String category
    ) {}
}
