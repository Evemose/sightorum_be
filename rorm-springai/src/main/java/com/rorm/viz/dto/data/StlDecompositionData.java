package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("""
    STL-decomposition payload: three stacked panels (trend / seasonal /
    remainder) sharing a common time axis.""")
public record StlDecompositionData(

    @JsonPropertyDescription("Time-ordered decomposition points. Each carries trend / seasonal / remainder at time t.")
    @JsonProperty(required = true)
    List<Point> points
) {

    @JsonClassDescription("One decomposition sample: trend + seasonal + remainder = observed at time t.")
    public record Point(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Trend component at time t.")
        @JsonProperty(required = true)
        double trend,

        @JsonPropertyDescription("Seasonal component at time t.")
        @JsonProperty(required = true)
        double seasonal,

        @JsonPropertyDescription("Residual (remainder) at time t.")
        @JsonProperty(required = true)
        double remainder
    ) {}
}
