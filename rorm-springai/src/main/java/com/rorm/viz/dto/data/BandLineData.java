package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("""
    Band-line payload: a central line value flanked by a min/max band
    at each point in time.
    
    Invariant: min <= value <= max for every point.""")
public record BandLineData(

    @JsonPropertyDescription("Time-ordered band points. Each has a time, a central value, and a [min, max] band.")
    @JsonProperty(required = true)
    List<BandPoint> points
) {

    @JsonClassDescription("One (t, value, min, max) point. min <= value <= max MUST hold.")
    public record BandPoint(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Central value of the band at time t.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Lower bound of the band. Must satisfy min <= value.")
        @JsonProperty(required = true)
        double min,

        @JsonPropertyDescription("Upper bound of the band. Must satisfy value <= max.")
        @JsonProperty(required = true)
        double max
    ) {}
}
