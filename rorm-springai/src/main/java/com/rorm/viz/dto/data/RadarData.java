package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Radar-chart payload: N named indicators, each with a normalization
    max, plus one or more named series.
    
    Invariant: every series's `values.length === indicators.length`.""")
public record RadarData(

    @JsonPropertyDescription("Radar axes. Order defines the clockwise rendering order.")
    @JsonProperty(required = true)
    List<Indicator> indicators,

    @JsonPropertyDescription("Series to plot on top of the radar grid.")
    @JsonProperty(required = true)
    List<Series> series
) {

    @JsonClassDescription("One radar axis.")
    public record Indicator(

        @JsonPropertyDescription("Indicator name rendered at the axis label.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Maximum value for this axis, used to normalize the polygon.")
        @JsonProperty(required = true)
        double max
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One radar series. values.length MUST equal indicators.length.")
    public record Series(

        @JsonPropertyDescription("Series name rendered in the legend.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional color role for this series.")
        @Nullable String color,

        @JsonPropertyDescription("Values on each axis, in indicator order. Length MUST equal indicators.length.")
        @JsonProperty(required = true)
        List<Double> values
    ) {}
}
