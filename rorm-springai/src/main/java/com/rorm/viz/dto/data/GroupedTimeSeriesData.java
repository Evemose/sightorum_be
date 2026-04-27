package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Structured multi-series time-series payload. Use when each series
    carries a named identity (line-chart, area-chart, base-vs-shifted
    overlay). For the flat single-series form see TimeSeriesData.""")
public record GroupedTimeSeriesData(

    @JsonPropertyDescription("Named series. Each series has its own ordered list of time/value points.")
    @JsonProperty(required = true)
    List<Series> series
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One named series with its own time/value points.")
    public record Series(

        @JsonPropertyDescription("Series name rendered in the legend.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional color role for this series. Falls back to chart default.")
        @Nullable String color,

        @JsonPropertyDescription("Time-ordered (time, value) points for this series.")
        @JsonProperty(required = true)
        List<Point> points
    ) {}

    @JsonClassDescription("Single (time, value) point of a grouped time series.")
    public record Point(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Numeric value at time `t`. Must be finite.")
        @JsonProperty(required = true)
        double value
    ) {}
}
