package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Small-multiples line payload: one line panel per named series, laid out in a grid.")
public record SmallMultiplesLineData(

    @JsonPropertyDescription("Panels, each rendered as its own line chart.")
    @JsonProperty(required = true)
    List<Panel> panels
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One line panel.")
    public record Panel(

        @JsonPropertyDescription("Panel name rendered above the panel.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional line color role for this panel.")
        @Nullable String color,

        @JsonPropertyDescription("Time-ordered (t, value) points for this panel's line.")
        @JsonProperty(required = true)
        List<Point> points
    ) {}

    @JsonClassDescription("Single (time, value) point of a small-multiples line panel.")
    public record Point(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Numeric value at time t.")
        @JsonProperty(required = true)
        double value
    ) {}
}
