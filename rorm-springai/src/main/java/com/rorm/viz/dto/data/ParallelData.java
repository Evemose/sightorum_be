package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.ColorRole;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Parallel-coordinates payload: N named axes and one or more polylines
    crossing every axis in order.
    
    Invariant: every line's `values.length === axes.length`.""")
public record ParallelData(

    @JsonPropertyDescription("Axes. Rendered left-to-right in list order.")
    @JsonProperty(required = true)
    List<Axis> axes,

    @JsonPropertyDescription("Polylines crossing every axis in order.")
    @JsonProperty(required = true)
    List<Line> lines
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One axis.")
    public record Axis(

        @JsonPropertyDescription("Axis name rendered at the top of the axis.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional explicit axis lower bound. Frontend infers from data when omitted.")
        @Nullable Double min,

        @JsonPropertyDescription("Optional explicit axis upper bound. Frontend infers from data when omitted.")
        @Nullable Double max
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One polyline. values.length MUST equal axes.length.")
    public record Line(

        @JsonPropertyDescription("Optional line identifier. Used for highlight matching.")
        @Nullable String name,

        @JsonPropertyDescription("Optional line color role.")
        @Nullable ColorRole color,

        @JsonPropertyDescription("Values on each axis, in axis order. Length MUST equal axes.length.")
        @JsonProperty(required = true)
        List<Double> values
    ) {}
}
