package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("""
    Joinpoint-regression payload: piecewise-linear segments connecting
    the fitted trend, plus explicit joinpoint markers.""")
public record JoinpointData(

    @JsonPropertyDescription("Fitted piecewise-linear segments, in time order.")
    @JsonProperty(required = true)
    List<Segment> segments,

    @JsonPropertyDescription("Detected joinpoints (segment break markers), in time order.")
    @JsonProperty(required = true)
    List<Point> joinpoints
) {

    @JsonClassDescription("One fitted segment spanning [from, to].")
    public record Segment(

        @JsonPropertyDescription("Segment start point.")
        @JsonProperty(required = true)
        Point from,

        @JsonPropertyDescription("Segment end point.")
        @JsonProperty(required = true)
        Point to
    ) {}

    @JsonClassDescription("A (time, value) point in a joinpoint regression.")
    public record Point(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Numeric value at time t.")
        @JsonProperty(required = true)
        double value
    ) {}
}
