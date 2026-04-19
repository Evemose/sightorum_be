package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.ColorRole;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for stacked-bar and stacked-bar-100 charts.
    
    Invariant:
    Every `series[i].values.length === categories.length`. The value at
    `values[j]` contributes to the stack at `categories[j]`.""")
public record StackedSeriesData(

    @JsonPropertyDescription("X-axis tick labels. One entry per stacked column.")
    @JsonProperty(required = true)
    List<String> categories,

    @JsonPropertyDescription("Series to stack. All series must share the same length as `categories`.")
    @JsonProperty(required = true)
    List<Series> series
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One stacked series. values.length MUST equal categories.length.")
    public record Series(

        @JsonPropertyDescription("Series name rendered in the legend.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional color role for this series. Falls back to chart default.")
        @Nullable ColorRole color,

        @JsonPropertyDescription("Per-category values. Length MUST equal categories.length.")
        @JsonProperty(required = true)
        List<Double> values
    ) {}
}
