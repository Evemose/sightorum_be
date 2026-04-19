package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("""
    QQ-plot payload: observed-vs-theoretical quantile pairs and a
    reference line for perfect agreement.""")
public record QQPlotData(

    @JsonPropertyDescription("Quantile pairs: each element is [theoreticalQuantile, observedQuantile].")
    @JsonProperty(required = true)
    List<List<Double>> points,

    @JsonPropertyDescription("Reference line (typically y = x) in chart coordinates.")
    @JsonProperty(required = true)
    ReferenceLine referenceLine
) {

    @JsonClassDescription("Reference line in QQ-plot chart coordinates.")
    public record ReferenceLine(

        @JsonPropertyDescription("Line start point as [x, y].")
        @JsonProperty(required = true)
        List<Double> from,

        @JsonPropertyDescription("Line end point as [x, y].")
        @JsonProperty(required = true)
        List<Double> to
    ) {}
}
