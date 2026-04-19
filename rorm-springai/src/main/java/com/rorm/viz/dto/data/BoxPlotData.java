package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Box-plot payload with pre-computed five-number summary and optional outliers per group.")
public record BoxPlotData(

    @JsonPropertyDescription("Groups plotted side-by-side along the category axis.")
    @JsonProperty(required = true)
    List<BoxPlotGroup> groups
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One box plot: five-number summary (min, q1, median, q3, max)
        plus optional explicit outliers.""")
    public record BoxPlotGroup(

        @JsonPropertyDescription("Group name rendered on the category axis.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Minimum (lower whisker endpoint).")
        @JsonProperty(required = true)
        double min,

        @JsonPropertyDescription("First quartile (25th percentile).")
        @JsonProperty(required = true)
        double q1,

        @JsonPropertyDescription("Median (50th percentile).")
        @JsonProperty(required = true)
        double median,

        @JsonPropertyDescription("Third quartile (75th percentile).")
        @JsonProperty(required = true)
        double q3,

        @JsonPropertyDescription("Maximum (upper whisker endpoint).")
        @JsonProperty(required = true)
        double max,

        @JsonPropertyDescription("Optional individual outliers outside [min, max] rendered as dots.")
        @Nullable List<Double> outliers
    ) {}
}
