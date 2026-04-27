package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Wrapped year-over-year payload: one line per year on a shared
    Jan..Dec x-axis.
    
    Invariant: `month` is an integer in [1, 12].""")
public record WrappedYoYData(

    @JsonPropertyDescription("One named line per year.")
    @JsonProperty(required = true)
    List<Series> series
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One year's series of monthly observations.")
    public record Series(

        @JsonPropertyDescription("Year this series represents.")
        @JsonProperty(required = true)
        int year,

        @JsonPropertyDescription("Optional color role for this year's line.")
        @Nullable String color,

        @JsonPropertyDescription("Monthly observations, sorted by `month` ascending.")
        @JsonProperty(required = true)
        List<Point> points
    ) {}

    @JsonClassDescription("Single monthly observation. `month` MUST be an integer in [1, 12].")
    public record Point(

        @JsonPropertyDescription("Month of year, 1 (January) .. 12 (December).")
        @JsonProperty(required = true)
        int month,

        @JsonPropertyDescription("Numeric value for that month.")
        @JsonProperty(required = true)
        double value
    ) {}
}
