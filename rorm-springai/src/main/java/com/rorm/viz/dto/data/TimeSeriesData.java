package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Flat time-series payload. Use for single-series charts, or provide a
    `series` discriminator on each point to emit multiple series in a
    flat form. For the structured multi-series shape see
    GroupedTimeSeriesData.""")
public record TimeSeriesData(

    @JsonPropertyDescription("Time-ordered data points. Each point carries a time, a numeric value, and an optional series key.")
    @JsonProperty(required = true)
    List<TimePoint> points
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One point of a time series. Time coordinates may be either
        ISO-8601 strings (preferred) or epoch milliseconds; see TimeCoord.""")
    public record TimePoint(

        @JsonPropertyDescription("Time coordinate: ISO-8601 string (preferred) or epoch milliseconds.")
        @JsonProperty(required = true)
        TimeCoord t,

        @JsonPropertyDescription("Numeric value at time `t`. Must be finite; drop the point otherwise.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Optional multi-series discriminator. Points sharing a `series` key form one line.")
        @Nullable String series
    ) {}
}
