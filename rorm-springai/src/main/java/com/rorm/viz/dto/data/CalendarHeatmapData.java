package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.LocalDate;
import java.util.List;

@JsonClassDescription("Calendar-heatmap payload for a single year. Missing days render as empty cells.")
public record CalendarHeatmapData(

    @JsonPropertyDescription("Year the heatmap represents, e.g. 2026.")
    @JsonProperty(required = true)
    int year,

    @JsonPropertyDescription("Values by day. Each point is a (YYYY-MM-DD date, value) pair.")
    @JsonProperty(required = true)
    List<Point> points
) {

    @JsonClassDescription("One daily observation. `date` is an ISO-8601 YYYY-MM-DD local date.")
    public record Point(

        @JsonPropertyDescription("Local date in ISO-8601 YYYY-MM-DD form.")
        @JsonProperty(required = true)
        LocalDate date,

        @JsonPropertyDescription("Numeric value for this day. Finite; drop the point otherwise.")
        @JsonProperty(required = true)
        double value
    ) {}
}
